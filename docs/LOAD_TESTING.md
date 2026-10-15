# Load Testing Guide

This document outlines the procedure to load test `boot-high-rps-sample` and evaluate its maximum sustainable RPS.

## Identical-Configuration Rule
Baseline and high-RPS runs must keep the dataset (including mutable/deletable pools), the traffic mix and all weights (including the four mutation weights), the target RPS, duration, warm-up, ramp, load-generator configuration, JVM configuration, database configuration, and infrastructure identical. Only the implementation under test changes.

## Infrastructure Startup Order

1. Start main infrastructure: docker compose -f docker/docker-compose.yml up -d
2. Start monitoring stack: docker compose -f docker/docker-compose-monitoring.yml up -d
3. Build the application and load-test classes: ./mvnw -DskipTests clean package
4. In a separate terminal, start the application and leave it running: java -jar target/boot-high-rps-sample-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
5. Wait for http://localhost:8080/actuator/health to report UP.
6. Generate data: ./mvnw exec:java -Dexec.mainClass="com.example.highrps.gatling.setup.DataGenerator" -Dexec.classpathScope=test

Alternatively, use `../scripts/run-gatling-test.ps1` — the self-contained script used by CI that handles infra startup, build, data generation, and the Gatling run in one step. If the app is already running manually (steps above), run `./mvnw gatling:test -Dprofile=<profile>` directly.

The data generator builds shared read/create pools, as well as dedicated mutable and deletable pools. The deletable pools are sized large enough so each run uses them as a non-repeating queue. For longer runs, increase the deletable pool sizes in your config to avoid starvation.

## Load Profiles

You can override Gatling profiles using -Dprofile=<name>:
- smoke: 10 RPS for 5 mins (plus 5 min warmup).
- normal: 50 RPS for 15 mins (plus 5 min warmup).
- high: 100 RPS for 15 mins (plus 5 min warmup).
- stress: Stepped load from 100, 250, 500, to 1000 RPS.

## Properties Configuration

Refer to src/test/resources/load-test.properties for all overridable settings.

Note: The CI `targetRps` and mutation-weight `workflow_dispatch` inputs are validated but not passed to the actual Gatling run, so they currently have no effect.

### Operation Weights
| Operation            | Default Weight | Purpose                           |
|----------------------|----------------|-----------------------------------|
| readPostWeight       | 41.0           | Read a post by ID                 |
| readCommentsWeight   | 20.0           | Read comments for a post          |
| readTagPostsWeight   | 10.0           | Read posts by tag                 |
| createCommentWeight  | 15.0           | Create a new comment              |
| createPostWeight     | 8.0            | Create a new post                 |
| registerAuthorWeight | 2.0            | Register a new author             |
| postUpdateWeight     | 1.0            | Update a post (mutable pool)      |
| postDeleteWeight     | 1.0            | Delete a post (deletable pool)    |
| commentUpdateWeight  | 1.0            | Update a comment (mutable pool)   |
| commentDeleteWeight  | 1.0            | Delete a comment (deletable pool) |

Mutation weights are kept low by default to simulate typical read-heavy CMS traffic, but are fully configurable.

## Baseline vs High RPS Comparison

Per-endpoint (per-API) statistics are the **primary** comparison view, providing granular insight into how specific API optimizations perform. Global statistics are provided as a complementary view.

CI (`../.github/workflows/boot-high-rps-sample-perf.yml`) runs HEAD once and compares against a persisted baseline file, rather than checking out two commits for a live dual run.

1. **Baseline**: Run a baseline and save the Gatling stats.json.
2. **High RPS**: Run your modified HEAD and save the Gatling stats.json.
3. **Compare**: Run `.\scripts\compare-results.ps1 -BaselineStats path/to/base.json -NewStats path/to/head.json`

## Viewing Reports
You can view the detailed Gatling HTML report at:
	target/gatling/<sim>/index.html

## Diagnostics Collection



Diagnostics are collected as a first-class output of each benchmark run.
* **What is collected:** JVM/app metrics (CPU, heap, GC, allocation, threads), database metrics (connections, pool utilization, query latency), host metrics (CPU/Memory), docker stats snapshots, application logs, and GC logs.
* **When/How:** Collected in CI via collect_diagnostics.py before the monitoring containers are torn down, ensuring data survives assertion or comparison failures.
* **Storage:** Locally or in CI, artifacts are stored in performance-results/baseline/<profile>/diagnostics/ and performance-results/high-rps/<profile>/diagnostics/.
* **Usage:** Use these signals alongside per-endpoint stats to correlate latency spikes or throughput caps with resource saturation.

## Post-Run Verification

The scenarios already contain built-in functional assertions:
- Author scenarios verify HTTP 201 and location URI. (Note: Author update/delete is intentionally excluded since authors are shared heavily across posts).
- Post creation verifies retrieved post matches requested author and tags.
- Comment creation verifies the linked post ID.
- Read scenarios verify data presence.
- **Update semantics**: A sampled updated resource returns its new fields on a follow-up GET, with relationships preserved.
- **Delete semantics**: A sampled deleted resource returns 404 Not Found on a follow-up GET.

## Portability (k6/Kubernetes)
The externalized workload model (scenarios, weights, feeders, data distribution, profiles, ramp) is fully reusable for a future k6 or Kubernetes-based distributed implementation. Only the tool syntax changes, while the logic, pools, and traffic distribution remain portable.

---

## JVM vs Native Comparison

This section defines the protocol, validity conditions, improvement criteria, and caveats for comparing the JVM and GraalVM Native Image runtimes on the same runner using the comparison workflow (`.github/workflows/boot-high-rps-sample-runtime-comparison.yml`).

### Required Identical Settings

Both runtimes **must** share all of the following in each dispatch:
- **Dataset**: identical seed, identical author/post/comment/tag pool sizes.
- **Weights**: identical load-operation weights (readPost, readComments, readTagPosts, createComment, createPost, registerAuthor, postUpdate, postDelete, commentUpdate, commentDelete).
- **Load shape**: identical profile (`stress` for throughput comparison), ramp, duration, and warm-up (minimum 3 minutes, applied to both runtimes).
- **Heap ceiling**: `-Xmx2g` for both (JVM: `-Xmx2g`, native: `-Xmx2g` passed to the native executable).
- **Observability**: 100% tracing sampling (`management.tracing.sampling.probability=1.0`), DEBUG logging, LGTM/Grafana handling identical.
- **Clean state**: each run starts from `-ResetState` to ensure the database and Redis are empty.

### Validity Conditions

A comparison dispatch is **valid** only if all of the following hold for both runtimes:

1. Both runtimes pass readiness (app reports `UP` at `/actuator/health`).
2. Completed drain with zero `lag` and zero `pending` for `batch-processor-group` on `events:queue` (confirmed by `check-drain.py wait`).
3. Zero DLQ delta (no new entries in `events:dlq` during the run).
4. Gatling KO rate ≤ `maxErrorRate` (the verify-step assertion threshold).

Any run that fails conditions 1–3 is invalid and must be repeated. Gatling KO above `maxErrorRate` (condition 4) does not invalidate the drain check but does render throughput numbers unreliable for performance comparison purposes.

### Improvement Criteria

Native is considered improved over JVM under `stress` if and only if **all** of the following hold:

1. Native global successful RPS ≥ JVM global successful RPS × 1.05 (at least 5% higher).
2. The absolute difference exceeds repeated-run spread (i.e., the gap is larger than run-to-run noise observed in multiple same-runtime dispatches).
3. Native error rate ≤ JVM error rate.
4. Native p99 ≤ JVM p99.
5. The result is **confirmed in at least two dispatches with opposite `order` values** (`jvm-first` and `native-first`) to rule out runner warm-up bias.

### Caveats

- **Community Serial GC**: GraalVM Community Edition uses Serial GC by default (not G1 or ZGC). JVM runs use ZGC (`-XX:+UseZGC`). This is a significant throughput disadvantage for native at high heap pressure. If Community results are close to JVM, Oracle GraalVM with G1 + PGO should be evaluated as a follow-up without adopting Community native.
- **No PGO**: Community Native Image does not include Profile-Guided Optimisation. The JVM benefits from JIT warm-up (C2) by the time the warm-up phase ends, while the native executable is already at peak performance but without PGO-optimised code paths.
- **Shared runner CPU**: GitHub-hosted runners share CPU with other jobs. Run-to-run variance can be 10–20% on shared runners. Always compare runs on the same runner and at the same time-of-day when possible.
- **Startup and memory**: Report startup time-to-ready and per-replica peak/average memory only as secondary benefits. These do not constitute the primary adoption criterion.

### Measured Results

> _Results to be filled in after dispatching the comparison workflow at least twice with opposite order values._

| Dispatch | Order | JVM Successful RPS | Native Successful RPS | Improvement | Valid? |
|----------|-------|--------------------|-----------------------|-------------|--------|
| —        | —     | —                  | —                     | —           | —      |

**Decision**: Pending. Proceed to Phase 4 (native adoption in perf workflow) only if native meets every throughput criterion above.
