#!/usr/bin/env python3
"""
check-drain.py: Drain check utility for the boot-high-rps-sample Redis event stream.

Two modes:
  snapshot  -- Record the current XLEN events:dlq BEFORE Gatling starts.
               Writes {dlq_before: N} to --out.

  wait      -- AFTER Gatling, poll XINFO GROUPS events:queue for
               batch-processor-group until both lag and pending are zero,
               or a timeout occurs.  Writes drain results next to the
               snapshot file.

Usage:
  python3 scripts/check-drain.py snapshot [--out <file>]
  python3 scripts/check-drain.py wait [--snapshot <file>] [--out <file>]
                                       [--timeout <seconds>] [--interval <seconds>]

Container names are taken from collect_diagnostics.py conventions:
  Redis master: redis-master
  PostgreSQL:   postgresql

Stream / consumer-group names match application.properties:
  Queue stream:  events:queue
  DLQ stream:    events:dlq
  Consumer group: batch-processor-group

NOTE: Stream length (XLEN) is intentionally NOT used as a drain signal because
XACK does not trim the stream — acknowledged messages remain in the stream until
a XTRIM or MAXLEN policy removes them.  The correct drain signal is
XINFO GROUPS lag=0 and pending=0 for the consumer group.
"""

import argparse
import json
import os
import subprocess
import sys
import time

# Container names — must match collect_diagnostics.py and docker-compose
REDIS_MASTER_CONTAINER = "redis-master"
POSTGRES_CONTAINER = "postgresql"

# Stream / group names — must match application.properties
QUEUE_STREAM = "events:queue"
DLQ_STREAM = "events:dlq"
CONSUMER_GROUP = "batch-processor-group"

# Default drain-wait settings
DEFAULT_TIMEOUT_SECONDS = 300   # 5 minutes
DEFAULT_INTERVAL_SECONDS = 5


def docker_exec_redis(args: list[str]) -> str | None:
    """Run a redis-cli command in the redis-master container and return stdout."""
    cmd = ["docker", "exec", REDIS_MASTER_CONTAINER, "redis-cli"] + args
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if result.returncode != 0:
            print(f"redis-cli error: {result.stderr.strip()}", file=sys.stderr)
            return None
        return result.stdout.strip()
    except Exception as exc:
        print(f"docker exec error ({' '.join(cmd)}): {exc}", file=sys.stderr)
        return None


def docker_exec_psql(sql: str) -> str | None:
    """Run a psql query in the postgresql container and return stdout."""
    cmd = [
        "docker", "exec", POSTGRES_CONTAINER,
        "psql", "-U", "appuser", "-d", "statsdb",
        "-t", "-c", sql,
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if result.returncode != 0:
            print(f"psql error: {result.stderr.strip()}", file=sys.stderr)
            return None
        return result.stdout.strip()
    except Exception as exc:
        print(f"docker exec error ({' '.join(cmd)}): {exc}", file=sys.stderr)
        return None


def get_dlq_len() -> int | None:
    """Return the current XLEN of events:dlq, or None on error."""
    raw = docker_exec_redis(["XLEN", DLQ_STREAM])
    if raw is None:
        return None
    try:
        return int(raw)
    except ValueError:
        print(f"Unexpected XLEN response: {raw!r}", file=sys.stderr)
        return None


def get_group_info() -> dict | None:
    """
    Return {lag: int, pending: int} for batch-processor-group, or None on error.

    XINFO GROUPS output format (Redis 7+):
      1) "name"
      2) "batch-processor-group"
      ...
      9) "lag"
      10) <lag>
      11) "pending"
      12) <pending>
    """
    raw = docker_exec_redis(["XINFO", "GROUPS", QUEUE_STREAM])
    if raw is None:
        return None

    # Parse the flat Redis list into key-value pairs for our group
    tokens = raw.splitlines()
    # XINFO GROUPS can return multiple groups; find ours
    in_group = False
    result: dict = {}
    i = 0
    while i < len(tokens):
        tok = tokens[i].strip()
        if tok == "name":
            i += 1
            if i < len(tokens) and tokens[i].strip() == CONSUMER_GROUP:
                in_group = True
                result = {"lag": None, "pending": None}
            else:
                in_group = False
        elif in_group and tok in ("lag", "pending"):
            i += 1
            if i < len(tokens):
                try:
                    result[tok] = int(tokens[i].strip())
                except ValueError:
                    pass
        i += 1

    if not result or result.get("lag") is None or result.get("pending") is None:
        # Fallback: try structured JSON-like parsing for newer Redis versions
        return None

    return result


def get_table_row_counts() -> dict:
    """Return row counts for the main business tables (authors, posts, comments)."""
    tables = {
        "authors": "SELECT COUNT(*) FROM authors",
        "posts": "SELECT COUNT(*) FROM posts",
        "post_comments": "SELECT COUNT(*) FROM post_comments",
    }
    counts = {}
    for table, sql in tables.items():
        raw = docker_exec_psql(sql)
        if raw is not None:
            try:
                counts[table] = int(raw)
            except ValueError:
                counts[table] = None
        else:
            counts[table] = None
    return counts


def cmd_snapshot(args: argparse.Namespace) -> int:
    """Record DLQ length before Gatling."""
    dlq_len = get_dlq_len()
    if dlq_len is None:
        print("ERROR: Could not read DLQ length.", file=sys.stderr)
        return 1

    data = {
        "dlq_before": dlq_len,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(data, f, indent=2)

    print(f"DLQ snapshot: dlq_before={dlq_len}  written to {args.out}")
    return 0


def cmd_wait(args: argparse.Namespace) -> int:
    """Wait for drain completion after Gatling, then record results."""
    # Load snapshot for DLQ-before baseline
    dlq_before = None
    if args.snapshot and os.path.isfile(args.snapshot):
        with open(args.snapshot) as f:
            snap = json.load(f)
        dlq_before = snap.get("dlq_before")
        print(f"Loaded DLQ snapshot: dlq_before={dlq_before}")

    timeout_sec = args.timeout
    interval_sec = args.interval
    deadline = time.monotonic() + timeout_sec

    print(
        f"Waiting up to {timeout_sec}s for {CONSUMER_GROUP} to drain "
        f"(lag=0, pending=0)..."
    )

    drain_start = time.monotonic()
    drain_success = False

    while time.monotonic() < deadline:
        info = get_group_info()
        if info is not None:
            lag = info.get("lag", -1)
            pending = info.get("pending", -1)
            elapsed = time.monotonic() - drain_start
            print(f"  [{elapsed:.0f}s] lag={lag}  pending={pending}")
            if lag == 0 and pending == 0:
                drain_success = True
                break
        else:
            print("  Could not read group info, retrying...", file=sys.stderr)
        time.sleep(interval_sec)

    drain_time_sec = round(time.monotonic() - drain_start, 1)

    # DLQ delta
    dlq_after = get_dlq_len()
    dlq_delta = None
    if dlq_before is not None and dlq_after is not None:
        dlq_delta = dlq_after - dlq_before

    # Row counts
    row_counts = get_table_row_counts()

    results = {
        "drain_success": drain_success,
        "drain_time_sec": drain_time_sec,
        "dlq_before": dlq_before,
        "dlq_after": dlq_after,
        "dlq_delta": dlq_delta,
        "row_counts": row_counts,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(results, f, indent=2)

    print(f"Drain results written to {args.out}")
    print(
        f"drain_success={drain_success}  drain_time={drain_time_sec}s  "
        f"dlq_delta={dlq_delta}  rows={row_counts}"
    )

    return 0 if drain_success else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="Drain-check utility for boot-high-rps-sample")
    sub = ap.add_subparsers(dest="command", required=True)

    # snapshot subcommand
    snap_p = sub.add_parser("snapshot", help="Record DLQ length before Gatling")
    snap_p.add_argument(
        "--out", default="target/run-metrics/drain-snapshot.json",
        help="Output file for snapshot data",
    )

    # wait subcommand
    wait_p = sub.add_parser("wait", help="Wait for drain after Gatling")
    wait_p.add_argument(
        "--snapshot", default=None,
        help="Snapshot file written by the 'snapshot' command (for DLQ baseline)",
    )
    wait_p.add_argument(
        "--out", default="target/run-metrics/drain-results.json",
        help="Output file for drain results",
    )
    wait_p.add_argument(
        "--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS,
        help=f"Maximum seconds to wait for drain (default {DEFAULT_TIMEOUT_SECONDS})",
    )
    wait_p.add_argument(
        "--interval", type=int, default=DEFAULT_INTERVAL_SECONDS,
        help=f"Polling interval in seconds (default {DEFAULT_INTERVAL_SECONDS})",
    )

    args = ap.parse_args()

    if args.command == "snapshot":
        return cmd_snapshot(args)
    elif args.command == "wait":
        return cmd_wait(args)
    else:
        ap.print_help()
        return 1


if __name__ == "__main__":
    sys.exit(main())
