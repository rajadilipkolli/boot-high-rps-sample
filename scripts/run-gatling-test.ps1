param(
    [string]$Profile = "smoke",
    [int]$DurationMinutes = 1,
    [int]$WarmupMinutes = 1,
    [double]$TargetRps = 0,
    # New in Task 2: select jvm (default) or native runtime
    [ValidateSet("jvm", "native")]
    [string]$Runtime = "jvm",
    # Reset all stateful infrastructure and generated data before the run
    [switch]$ResetState,
    # Run drain check after Gatling (wait mode) and snapshot DLQ before (snapshot mode)
    [switch]$DrainCheck
)

$DataDirectory = "target/loadtest-data"
$CompletionMarker = Join-Path $DataDirectory ".complete"
$RequiredFeederFiles = @{
    "authors.csv" = "email"
    "tags.csv" = "tag"
    "posts.csv" = "postId,weight"
    "post_tags.csv" = "tag,postId"
    "mutable_posts.csv" = "postId,authorEmail"
    "deletable_posts.csv" = "postId,authorEmail"
    "mutable_comments.csv" = "postId,commentId,authorEmail"
    "deletable_comments.csv" = "postId,commentId,authorEmail"
}

function Test-RequiredFeederFiles {
    foreach ($entry in $RequiredFeederFiles.GetEnumerator()) {
        $filePath = Join-Path $DataDirectory $entry.Key
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            return $false
        }

        $lines = @(Get-Content -LiteralPath $filePath)
        if ($lines.Count -le 1 -or $lines[0] -ne $entry.Value) {
            return $false
        }
    }

    return $true
}

if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    if ($env:GRAALVM_HOME -and (Test-Path $env:GRAALVM_HOME)) {
        $env:JAVA_HOME = $env:GRAALVM_HOME
    }
}

# ── Derive Docker image tag and dockerfile from runtime ──────────────────────
# These must be set before any Compose call because the main stack starts before
# packaging is complete.
if ($Runtime -eq "native") {
    $env:APP_DOCKERFILE = "Dockerfile.native"
    $env:APP_IMAGE_TAG  = "native"
} else {
    $env:APP_DOCKERFILE = "Dockerfile"
    $env:APP_IMAGE_TAG  = "latest"
}

Write-Host "Runtime: $Runtime  Dockerfile: $($env:APP_DOCKERFILE)  Image tag: $($env:APP_IMAGE_TAG)"

# ── Reset state ───────────────────────────────────────────────────────────────
if ($ResetState) {
    Write-Host "ResetState: tearing down all stacks and clearing data..."

    # Bring down all three stacks with volumes to ensure clean DB and Redis state
    docker compose -p boot-high-rps-sample -f docker/docker-compose.yml down -v
    docker compose -p boot-high-rps-sample -f docker/docker-compose-sentinel.yml down -v
    docker compose -p boot-high-rps-sample -f docker/docker-compose-monitoring.yml down -v

    # Delete generated load-test data (including .complete marker) so the data
    # generator always re-populates a clean database
    if (Test-Path -LiteralPath $DataDirectory) {
        Write-Host "Deleting $DataDirectory (including .complete marker)..."
        Remove-Item -LiteralPath $DataDirectory -Recurse -Force
    }
}

Stop-Process -Name java -ErrorAction SilentlyContinue

Write-Host "Starting infrastructure..."
docker compose -p boot-high-rps-sample -f docker/docker-compose-sentinel.yml up -d
docker compose -p boot-high-rps-sample -f docker/docker-compose.yml up -d postgresqldb

# Skip grafana-lgtm for stress profile — at 1000 RPS it consumes ~138% CPU and 3 GB RAM
# on the same host, competing with the app and inflating latency.
# All other profiles start LGTM so traces/metrics are available in Grafana.
if ($Profile -eq "stress") {
    Write-Host "Stress profile: starting exporters only (grafana-lgtm skipped to free CPU/RAM)..."
    docker compose -p boot-high-rps-sample -f docker/docker-compose-monitoring.yml stop grafana-lgtm
    docker compose -p boot-high-rps-sample -f docker/docker-compose-monitoring.yml up -d
} else {
    docker compose -p boot-high-rps-sample -f docker/docker-compose-monitoring.yml --profile monitoring up -d
}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Monitoring startup failed!"
    exit 1
}

# ── Build phase ───────────────────────────────────────────────────────────────
if ($Runtime -eq "native") {
    # For native runs skip host-side JAR packaging; the Dockerfile.native
    # compiles the native executable inside the builder container.
    Write-Host "Native runtime: skipping host JAR packaging. Building Docker image..."
    docker compose -p boot-high-rps-sample -f docker/docker-compose.yml build app
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Native Docker image build failed!"
        exit 1
    }
} else {
    Write-Host "Building app (JVM)..."
    if ($env:OS -eq 'Windows_NT') {
        cmd /c "mvnw.cmd clean package -DskipTests"
    } else {
        sh -c "./mvnw clean package -DskipTests"
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Maven build failed!"
        exit 1
    }

    Write-Host "Building Docker image (JVM)..."
    docker compose -p boot-high-rps-sample -f docker/docker-compose.yml build app
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker image build failed!"
        exit 1
    }
}

# ── Start app and measure time-to-ready ──────────────────────────────────────
Write-Host "Starting app behind load balancer..."
$StartTime = [System.Diagnostics.Stopwatch]::StartNew()
docker compose -p boot-high-rps-sample -f docker/docker-compose.yml up -d --scale app=2
docker restart boot-high-rps-sample-nginx-1

Write-Host "Waiting for app to start..."
$AppReady = $false
for ($i = 0; $i -lt 120; $i++) {
    try {
        $Response = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get -ErrorAction Stop
        if ($Response.status -eq 'UP') {
            $AppReady = $true
            break
        }
    } catch {
        # Ignore and retry
    }
    Start-Sleep -Seconds 2
}
$StartTime.Stop()
$TimeToReadySeconds = [math]::Round($StartTime.Elapsed.TotalSeconds, 2)

if (-not $AppReady) {
    Write-Host "App failed to start!"
    docker logs boot-high-rps-sample-app-1
    exit 1
}

Write-Host "App is UP in ${TimeToReadySeconds}s"

# ── Write per-run metrics JSON ────────────────────────────────────────────────
$MetricsDir = "target/run-metrics"
New-Item -ItemType Directory -Path $MetricsDir -Force | Out-Null
$MetricsFile = Join-Path $MetricsDir "runtime-metrics-$Runtime.json"
@{
    runtime         = $Runtime
    profile         = $Profile
    timeToReadySec  = $TimeToReadySeconds
    timestamp       = (Get-Date -Format "o")
} | ConvertTo-Json | Set-Content -Path $MetricsFile

# ── Data generation ───────────────────────────────────────────────────────────
$RequiredFeederFilesValid = Test-RequiredFeederFiles
if ((Test-Path -LiteralPath $CompletionMarker -PathType Leaf) -and $RequiredFeederFilesValid) {
    Write-Host "Data already present under target/loadtest-data. Skipping Data Generator..."
} else {
    if (Test-Path -LiteralPath $DataDirectory) {
        Write-Host "Load-test data is incomplete. Clearing it before regeneration..."
        Remove-Item -LiteralPath $DataDirectory -Recurse -Force
    }

    Write-Host "App is UP! Running Data Generator..."
    if (-not (Test-Path "target/test-classes/com/example/highrps/gatling/setup/DataGenerator.class")) {
        Write-Host "Compiling test classes for Data Generator..."
        if ($env:OS -eq 'Windows_NT') {
            cmd /c "mvnw.cmd test-compile -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true"
        } else {
            sh -c "./mvnw test-compile -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true"
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Host "Data Generator test compilation failed!"
            exit 1
        }
    }
    if ($env:OS -eq 'Windows_NT') {
        cmd /c "mvnw.cmd exec:java -Dexec.mainClass=com.example.highrps.gatling.setup.DataGenerator -Dexec.classpathScope=test -DdataDir=$DataDirectory -Dprofile=$Profile"
    } else {
        sh -c "./mvnw exec:java -Dexec.mainClass=com.example.highrps.gatling.setup.DataGenerator -Dexec.classpathScope=test -DdataDir=$DataDirectory -Dprofile=$Profile"
    }

    if ($LASTEXITCODE -ne 0) {
        Write-Host "Data Generator failed!"
        exit 1
    }

    if (-not (Test-RequiredFeederFiles)) {
            Write-Host "Data Generator completed without producing valid feeder files!"
            exit 1
    }

    New-Item -ItemType File -Path $CompletionMarker -Force | Out-Null
}

# ── DrainCheck: DLQ snapshot before Gatling ──────────────────────────────────
$PythonCmd = if (Get-Command python3 -ErrorAction SilentlyContinue) { "python3" } else { "python" }
if ($DrainCheck) {
    Write-Host "DrainCheck: recording DLQ snapshot before Gatling..."
    & $PythonCmd scripts/check-drain.py snapshot --out target/run-metrics/drain-snapshot-$Runtime.json
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Warning: DrainCheck snapshot failed (non-fatal). Continuing..."
    }
}

# ── Gatling run ───────────────────────────────────────────────────────────────
$ExtraArgs = ""
if ($Profile -eq "stress" -and $PSBoundParameters.ContainsKey("TargetRps")) {
    Write-Host "TargetRps cannot be overridden for the stress profile."
    exit 1
}
if ($TargetRps -gt 0) {
    $ExtraArgs += " -DtargetRps=$TargetRps"
}

$GatlingLog = Join-Path $MetricsDir "gatling-$Runtime.log"
Write-Host "Running Gatling with Profile: $Profile..."
if ($env:OS -eq 'Windows_NT') {
    cmd /c "mvnw.cmd gatling:test -Dprofile=$Profile -DdurationMinutes=$DurationMinutes -DwarmupMinutes=$WarmupMinutes $ExtraArgs" 2>&1 | Tee-Object -FilePath $GatlingLog
} else {
    sh -c "./mvnw gatling:test -Dprofile=$Profile -DdurationMinutes=$DurationMinutes -DwarmupMinutes=$WarmupMinutes $ExtraArgs" 2>&1 | Tee-Object -FilePath $GatlingLog
}
$GatlingExitCode = $LASTEXITCODE

# ── DrainCheck: wait mode after Gatling ──────────────────────────────────────
# Run drain wait even if Gatling assertions failed — drain completion is an
# independent validity criterion.
if ($DrainCheck) {
    Write-Host "DrainCheck: waiting for stream drain (lag=0, pending=0)..."
    & $PythonCmd scripts/check-drain.py wait `
        --snapshot target/run-metrics/drain-snapshot-$Runtime.json `
        --out target/run-metrics/drain-results-$Runtime.json
    if ($LASTEXITCODE -ne 0) {
        Write-Host "DrainCheck: drain did not complete cleanly!"
    }
}

exit $GatlingExitCode
