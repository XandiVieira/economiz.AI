# start-docker-wait.ps1 -- bring the Docker engine up after logon, WITH RETRY.
#
# Why this exists: the old "start Docker engine" task just launched
# 'Docker Desktop.exe' once. If the GUI came up wedged (white window /
# orphaned com.docker.backend holding the pipe), the process kept "running"
# so the task counted as success and never retried -- the engine stayed down
# until someone fixed it by hand. This script polls for the engine and
# actually relaunches a CLEAN instance if it doesn't come up in time.
#
# Triggered at logon by the "economizai - start Docker engine" task
# (re-pointed here by setup-autostart.ps1). Safe to run by hand too.
#
# ASCII-only for Windows PowerShell 5.1.

$dockerExe   = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
$maxAttempts = 4            # clean (re)launches before giving up
$pollSeconds = 10          # gap between 'docker version' polls
$pollsPerTry = 18          # 18 * 10s = 3 min wait per launch attempt
$dataRoot    = $env:ECONOMIZAI_DATA_ROOT; if (-not $dataRoot) { $dataRoot = "C:\economizai-data" }
New-Item -ItemType Directory -Force -Path (Join-Path $dataRoot "logs") | Out-Null
$logFile     = Join-Path $dataRoot "logs\docker-recovery.log"

function Log($msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Host $line
    try { Add-Content -Path $logFile -Value $line -Encoding ascii -ErrorAction Stop } catch {}
}

function Engine-Up {
    $null = & docker version --format '{{.Server.Version}}' 2>$null
    return ($LASTEXITCODE -eq 0)
}

# Kill any wedged Docker GUI/backend processes and stop the service for a
# truly clean relaunch. Needs the current session's rights; the logon task
# runs at RunLevel Highest so this can touch the protected processes.
function Clean-Slate {
    Log "clean-slate: killing docker processes + stopping service"
    Get-Process | Where-Object { $_.Name -like '*docker*' -and $_.Name -ne 'com.docker.service' } |
        Stop-Process -Force -ErrorAction SilentlyContinue
    & cmd /c 'taskkill /F /IM "Docker Desktop.exe" /T' 2>$null | Out-Null
    & cmd /c 'taskkill /F /IM com.docker.backend.exe /T' 2>$null | Out-Null
    Stop-Service com.docker.service -Force -ErrorAction SilentlyContinue
    & wsl --shutdown 2>$null | Out-Null
    Start-Sleep -Seconds 3
}

if (-not (Test-Path $dockerExe)) { Log "ERROR: Docker Desktop.exe not found at $dockerExe"; exit 1 }

# Make sure the context points at the WSL engine (it resets to a dead
# npipe 'default' on some restarts -- documented gotcha).
& docker context use desktop-linux 2>$null | Out-Null

if (Engine-Up) { Log "engine already up -- nothing to do"; exit 0 }

for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    Log ("attempt {0}/{1}: starting Docker engine" -f $attempt, $maxAttempts)

    # On every attempt after the first, wipe the slate -- a hung first launch
    # is the exact failure mode we are recovering from.
    if ($attempt -gt 1) { Clean-Slate }

    # The privileged service must be Running before the GUI can bring up the
    # WSL backend. Set-StartupType to Automatic is done once by setup-autostart;
    # start it here defensively in case it is still Manual/Stopped.
    if ((Get-Service com.docker.service -ErrorAction SilentlyContinue).Status -ne 'Running') {
        Start-Service com.docker.service -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }

    Start-Process $dockerExe

    for ($p = 1; $p -le $pollsPerTry; $p++) {
        Start-Sleep -Seconds $pollSeconds
        if (Engine-Up) {
            $secs = $p * $pollSeconds
            Log ("engine UP after attempt {0} (~{1}s)" -f $attempt, $secs)
            # Containers have restart=unless-stopped; give them a moment, then report.
            Start-Sleep -Seconds 5
            $names = (& docker ps --format '{{.Names}}' 2>$null) -join ', '
            Log "containers: $names"
            exit 0
        }
    }
    Log ("attempt {0}: engine still down after {1}s" -f $attempt, ($pollsPerTry * $pollSeconds))
}

Log "ERROR: engine did not come up after $maxAttempts attempts -- manual check needed"
exit 1
