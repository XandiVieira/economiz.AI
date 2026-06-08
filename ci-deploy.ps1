# Called by the deploy-dev-server workflow on the self-hosted runner.
# Runs from the runner's checkout dir. Copies .env, rebuilds, verifies health.
# Single script (not multiple workflow steps) to avoid per-step PowerShell
# exit-code quirks. Echoes everything so failures are visible in the Actions UI.

# NOTE: do NOT use ErrorActionPreference=Stop here. Native commands (docker)
# write normal status to stderr (e.g. 'Current context is now ...'), which Stop
# would treat as a terminating error. We check $LASTEXITCODE explicitly instead.
$ErrorActionPreference = "Continue"
Write-Host "=== ci-deploy start ==="
Write-Host "cwd: $(Get-Location)"

# 1) Bring in the gitignored .env (kept at C:\actions-runner\.env, readable by
#    the service account; the OneDrive copy is not reliably readable).
$src = "C:\actions-runner\.env"
if (-not (Test-Path $src)) { Write-Host "::error::.env not found at $src"; exit 1 }
Copy-Item -LiteralPath $src -Destination (Join-Path (Get-Location) ".env") -Force
Write-Host "copied .env (exists: $(Test-Path '.env'))"

# 1b) Ensure the host log dir exists BEFORE compose up. The app writes its
#     persistent rolling log to ./logs/app (bind-mounted to /var/log/economizai).
#     If the source dir is missing at container-create time, Docker Desktop/WSL2
#     does not mount the real host folder and the log silently goes nowhere.
$logDir = Join-Path (Get-Location) "logs\app"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
Write-Host "log dir ready: $logDir"

# 2) Rebuild + restart. Pin docker context (resets to 'default' across restarts).
#    docker writes normal status to stderr; capture it to a string (so it never
#    propagates as an error to a parent shell running with ErrorActionPreference
#    Stop) and only judge success by $LASTEXITCODE.
$ctxOut = (& cmd /c "docker context use desktop-linux 2>&1")
Write-Host "context: $ctxOut"
$buildOut = (& cmd /c "docker compose --profile server up -d --build 2>&1")
$buildOut | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { Write-Host "::error::docker compose up failed (exit $LASTEXITCODE)"; exit 1 }

# 3) Verify health.
$ok = $false
for ($i = 0; $i -lt 20; $i++) {
    try {
        $code = (Invoke-WebRequest "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 8).StatusCode
        if ($code -eq 200) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 6
}
if (-not $ok) { Write-Host "::error::App did not become healthy after deploy"; exit 1 }
Write-Host "=== ci-deploy OK: dev server healthy on the new code ==="
