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
#     persistent rolling log to C:\economizai-data\logs\app (absolute machine
#     path, bind-mounted to /var/log/economizai — see docker-compose.yml). It is
#     OUTSIDE this checkout so logs never pollute the runner's git tree. If the
#     source dir is missing at container-create time, Docker Desktop/WSL2 does
#     not mount the real host folder and the log silently goes nowhere.
$dataRoot = $env:ECONOMIZAI_DATA_ROOT; if (-not $dataRoot) { $dataRoot = "C:\economizai-data" }
$logDir = Join-Path $dataRoot "logs\app"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
Write-Host "log dir ready: $logDir"

# 2) Rebuild + restart. Pin docker context (resets to 'default' across restarts).
#    docker writes normal status to stderr; capture it to a string (so it never
#    propagates as an error to a parent shell running with ErrorActionPreference
#    Stop) and only judge success by $LASTEXITCODE.
$ctxOut = (& cmd /c "docker context use desktop-linux 2>&1")
Write-Host "context: $ctxOut"
# Load env explicitly from the trusted .env (the same file copied in step 1) so the
# build never relies on cwd-relative .env discovery — keeps SMTP/JWT/etc. config in.
# --force-recreate so a change to an EXISTING env value (e.g. CORS_ORIGINS) actually
# reaches the container — without it Compose reuses the running container's old env
# when only the .env value changed (not the image or the compose service definition).
$buildOut = (& cmd /c "docker compose --env-file `"$src`" --profile server up -d --build --force-recreate 2>&1")
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

# 4) Self-heal the public front door. cloudflared occasionally dies and its
#    Scheduled Task only fires at logon, leaving the Worker's KV origin pointing
#    at a dead *.trycloudflare.com host (Cloudflare 1016 for every client) even
#    though the app is healthy locally. Since this script runs ON the box, check
#    the PERMANENT public URL and (re)start the tunnel task when it's broken.
#    Best-effort: a failure here must never fail an otherwise good deploy.
$tunnelTask = "economizai - cloudflare tunnel"
try {
    $publicOk = $false
    try {
        $code = (Invoke-WebRequest "https://economizai.economizai.workers.dev/actuator/health" -UseBasicParsing -TimeoutSec 15).StatusCode
        if ($code -eq 200) { $publicOk = $true }
    } catch { }
    if ($publicOk) {
        Write-Host "public URL healthy — tunnel OK"
    } else {
        Write-Host "public URL unhealthy — restarting tunnel task '$tunnelTask'"
        Stop-ScheduledTask -TaskName $tunnelTask -ErrorAction SilentlyContinue
        Start-ScheduledTask -TaskName $tunnelTask -ErrorAction Stop
        Write-Host "tunnel task started — waiting for the new origin to publish"
        Start-Sleep -Seconds 60
        $after = 0
        try { $after = (Invoke-WebRequest "https://economizai.economizai.workers.dev/actuator/health" -UseBasicParsing -TimeoutSec 15).StatusCode } catch { }
        $taskState = (Get-ScheduledTask -TaskName $tunnelTask).State
        $taskInfo = Get-ScheduledTaskInfo -TaskName $tunnelTask
        Write-Host ("tunnel restart result: public={0} taskState={1} lastRun={2} lastResult={3}" -f $after, $taskState, $taskInfo.LastRunTime, $taskInfo.LastTaskResult)
        $tunnelLog = Join-Path $dataRoot "logs\tunnel.log"
        if (Test-Path $tunnelLog) {
            Write-Host "--- tunnel.log tail ---"
            Get-Content $tunnelLog -Tail 12 | ForEach-Object { Write-Host $_ }
        }
    }
} catch { Write-Host "::warning::tunnel self-heal skipped: $_" }
Write-Host "=== ci-deploy OK: dev server healthy on the new code ==="
