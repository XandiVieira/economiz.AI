# economizai dev-server updater: pull latest code, rebuild, restart.
#
# Manual:   powershell -ExecutionPolicy Bypass -File .\update-server.ps1
# Auto:     run by the "economizai - auto update" Scheduled Task (see
#           setup-autoupdate.ps1). Only rebuilds when there are NEW commits,
#           so it's cheap to run often. Logs to update-server.log.
# ---------------------------------------------------------------------------
$ErrorActionPreference = "Continue"
$repo    = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$branch  = "development"
$logFile = Join-Path $repo "update-server.log"
function Log($m){ Add-Content -Path $logFile -Value ("{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

Set-Location $repo
& docker context use desktop-linux *> $null

# 1) Fetch and compare local vs remote on the tracked branch.
& git fetch origin $branch 2>&1 | Out-Null
$local  = (& git rev-parse $branch).Trim()
$remote = (& git rev-parse "origin/$branch").Trim()

if ($local -eq $remote) {
    # No new commits. Quiet unless on the hour, to avoid log spam.
    if ((Get-Date).Minute -lt 5) { Log "noop up-to-date ($($local.Substring(0,7)))" }
    exit 0
}

Log "update new commits: $($local.Substring(0,7)) -> $($remote.Substring(0,7))"

# 2) Guard: don't clobber uncommitted local changes (e.g. a hotfix in progress).
$dirty = (& git status --porcelain) | Where-Object { $_ -notmatch '(\.env|\.log|current-tunnel-url\.txt)$' }
if ($dirty) {
    Log "ABORT working tree has uncommitted changes; skipping auto-update. Commit/stash first."
    exit 1
}

# 3) Pull (fast-forward only - never create surprise merge commits on the server).
$pull = & git merge --ff-only "origin/$branch" 2>&1
Log ("pull " + ($pull -join " | "))

# 4) Rebuild + restart only the app (DB + volume untouched, data preserved).
$out = & cmd /c "docker compose --profile server up -d --build 2>&1"
Log ("rebuild " + (($out | Select-Object -Last 3) -join " | "))

# 5) Verify health came back.
Start-Sleep -Seconds 15
$code = try { (Invoke-WebRequest "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 10).StatusCode } catch { 0 }
if ($code -eq 200) { Log "ok healthy after update (now $($remote.Substring(0,7)))" }
else               { Log "WARN not healthy after update (code=$code) - check logs" }
