import os
import subprocess
import json
import urllib.request

def run_cmd(cmd, outfile):
    try:
        with open(outfile, 'w') as f:
            subprocess.run(cmd, shell=True, stdout=f, stderr=subprocess.STDOUT)
    except Exception as e:
        print(f"Error running {cmd}: {e}")

def fetch_url(url, outfile):
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as response:
            with open(outfile, 'wb') as f:
                f.write(response.read())
    except Exception as e:
        print(f"Error fetching {url}: {e}")

def main():
    out_dir = "diagnostics"
    os.makedirs(out_dir, exist_ok=True)

    print("Collecting diagnostics...")
    run_cmd("docker stats --no-stream", f"{out_dir}/docker-stats.txt")
    run_cmd("docker ps", f"{out_dir}/docker-ps.txt")

    # Logs
    run_cmd("docker logs boot-high-rps-sample-app-1", f"{out_dir}/app-1.log")
    run_cmd("docker logs boot-high-rps-sample-app-2", f"{out_dir}/app-2.log")
    run_cmd("docker logs boot-high-rps-sample-postgresqldb-1", f"{out_dir}/postgres.log")
    run_cmd("docker logs boot-high-rps-sample-redis-1", f"{out_dir}/redis.log")
    run_cmd("docker logs node-exporter", f"{out_dir}/node-exporter.log")
    run_cmd("docker logs postgres-exporter", f"{out_dir}/postgres-exporter.log")

    # Metrics
    fetch_url("http://localhost:8080/actuator/metrics", f"{out_dir}/actuator-metrics-list.json")
    fetch_url("http://localhost:8080/actuator/prometheus", f"{out_dir}/app-prometheus.txt")
    fetch_url("http://localhost:9187/metrics", f"{out_dir}/postgres-exporter-metrics.txt")
    fetch_url("http://localhost:9100/metrics", f"{out_dir}/node-exporter-metrics.txt")

    print("Diagnostics collected in diagnostics/")

if __name__ == '__main__':
    main()
