# Called by the deploy-dev-server workflow on the self-hosted runner.
# Runs from the runner's checkout dir. Copies .env, rebuilds, verifies health.
# Single script (not multiple workflow steps) to avoid per-step PowerShell
# exit-code quirks. Echoes everything so failures are visible in the Actions UI.

$ErrorActionPreference = "Stop"
Write-Host "=== ci-deploy start ==="
Write-Host "cwd: $(Get-Location)"

# 1) Bring in the gitignored .env (kept at C:\actions-runner\.env, readable by
#    the service account; the OneDrive copy is not reliably readable).
$src = "C:\actions-runner\.env"
if (-not (Test-Path $src)) { throw ".env not found at $src" }
Copy-Item -LiteralPath $src -Destination (Join-Path (Get-Location) ".env") -Force
Write-Host "copied .env (exists: $(Test-Path '.env'))"

# 2) Rebuild + restart. Pin docker context (resets to 'default' across restarts).
& docker context use desktop-linux 2>$null
& docker compose --profile server up -d --build
if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }

# 3) Verify health.
$ok = $false
for ($i = 0; $i -lt 20; $i++) {
    try {
        $code = (Invoke-WebRequest "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 8).StatusCode
        if ($code -eq 200) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 6
}
if (-not $ok) { throw "App did not become healthy after deploy" }
Write-Host "=== ci-deploy OK: dev server healthy on the new code ==="
