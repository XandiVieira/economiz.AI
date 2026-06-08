# economizai stack watchdog — keeps the dev server up.
# Invoked by the "economizai - stack watchdog" Scheduled Task (at startup +
# every 5 min). Safe to run manually too. Logs to the machine data dir.
# ---------------------------------------------------------------------------
$repo    = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$dataRoot = $env:ECONOMIZAI_DATA_ROOT; if (-not $dataRoot) { $dataRoot = "C:\economizai-data" }
New-Item -ItemType Directory -Force -Path (Join-Path $dataRoot "logs") | Out-Null
$logFile = Join-Path $dataRoot "logs\stack-watchdog.log"
$compose = "docker compose --profile server"
$health  = "http://localhost:8080/actuator/health"

function Log($msg){
    $line = "{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Add-Content -Path $logFile -Value $line
}

Set-Location $repo

# 1) Wait for the Docker engine to answer (Docker Desktop may still be booting
#    after a reboot). Up to ~3 min. Pin the correct context first (it resets to
#    the dead 'default' pipe on each Docker restart).
& docker context use desktop-linux *> $null
$engineUp = $false
for ($i=0; $i -lt 36; $i++) {
    $v = & docker version --format '{{.Server.Version}}' 2>$null
    if ($LASTEXITCODE -eq 0 -and $v) { $engineUp = $true; break }
    Start-Sleep -Seconds 5
}
if (-not $engineUp) { Log "engine.down docker did not answer after ~3min; aborting this run"; exit 0 }

# 2) Is the app already healthy? If so, nothing to do.
try {
    $code = (Invoke-WebRequest -Uri $health -UseBasicParsing -TimeoutSec 8).StatusCode
} catch { $code = 0 }

if ($code -eq 200) {
    # quiet success — only log occasionally to avoid log spam
    if ((Get-Date).Minute % 30 -eq 0) { Log "ok app healthy (200)" }
    exit 0
}

# 3) Not healthy -> bring the stack up (idempotent; starts only what's down).
Log "recover app not healthy (code=$code); running compose up"
$out = & cmd /c "$compose up -d 2>&1"
Log ("compose.up " + ($out -join " | "))

# 4) Re-check after a short boot wait.
Start-Sleep -Seconds 12
try { $code2 = (Invoke-WebRequest -Uri $health -UseBasicParsing -TimeoutSec 8).StatusCode } catch { $code2 = 0 }
if ($code2 -eq 200) { Log "recover.ok app healthy after restart" }
else                { Log "recover.pending app still not 200 (code=$code2); will retry next cycle" }
