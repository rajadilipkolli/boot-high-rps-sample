param(
    [string]$Profile = "smoke",
    [int]$DurationMinutes = 1,
    [int]$WarmupMinutes = 1
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

Stop-Process -Name java -ErrorAction SilentlyContinue

Write-Host "Starting infrastructure..."
docker compose -p boot-high-rps-sample -f docker/docker-compose-sentinel.yml up -d
docker compose -p boot-high-rps-sample -f docker/docker-compose.yml up -d
docker compose -p boot-high-rps-sample -f docker/docker-compose-monitoring.yml up -d

Write-Host "Building app..."
cmd /c "mvnw.cmd clean package -DskipTests"
docker compose -p boot-high-rps-sample -f docker/docker-compose.yml build app
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!"
    exit 1
}

Write-Host "Starting app behind load balancer..."
docker-compose -p boot-high-rps-sample -f docker/docker-compose.yml up -d --scale app=2
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

if (-not $AppReady) {
    Write-Host "App failed to start!"
    docker logs boot-high-rps-sample-app-1
    exit 1
}

$RequiredFeederFilesValid = Test-RequiredFeederFiles
if ((Test-Path -LiteralPath $CompletionMarker -PathType Leaf) -and $RequiredFeederFilesValid) {
    Write-Host "Data already present under target/loadtest-data. Skipping Data Generator..."
} else {
    if (Test-Path -LiteralPath $DataDirectory) {
        Write-Host "Load-test data is incomplete. Clearing it before regeneration..."
        Remove-Item -LiteralPath $DataDirectory -Recurse -Force
    }

    Write-Host "App is UP! Running Data Generator..."
    cmd /c "mvnw.cmd exec:java -Dexec.mainClass=com.example.highrps.gatling.setup.DataGenerator -Dexec.classpathScope=test -DdataDir=$DataDirectory -Dprofile=$Profile"

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

Write-Host "Running Gatling with Profile: $Profile..."
cmd /c "mvnw.cmd gatling:test -Dprofile=$Profile -DdurationMinutes=$DurationMinutes -DwarmupMinutes=$WarmupMinutes"
$GatlingExitCode = $LASTEXITCODE

exit $GatlingExitCode
