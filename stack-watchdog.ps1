# economizai stack watchdog — keeps the dev server up.
# Invoked by the "economizai - stack watchdog" Scheduled Task (at startup +
# every 5 min). Safe to run manually too. Logs to the machine data dir.
# ---------------------------------------------------------------------------
$repo    = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$dataRoot = $env:ECONOMIZAI_DATA_ROOT; if (-not $dataRoot) { $dataRoot = "C:\economizai-data" }
New-Item -ItemType Directory -Force -Path (Join-Path $dataRoot "logs") | Out-Null
$logFile = Join-Path $dataRoot "logs\stack-watchdog.log"
# Always load env from the TRUSTED .env (service-account-readable, outside OneDrive)
# via an explicit --env-file. The OneDrive checkout's .env is "not reliably readable"
# (sync locks / permissions), so a bare `compose up` from $repo silently fell back to
# compose defaults -> empty SMTP creds -> auth emails went to DEV-MODE after a reboot.
# Pinning --env-file makes every recovery path load the real SMTP/JWT/etc. config.
$trustedEnv = "C:\actions-runner\.env"
$envArg = if (Test-Path $trustedEnv) { "--env-file `"$trustedEnv`"" } else { "" }
$compose = "docker compose $envArg --profile server"
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
#    First `up -d` starts anything that's down (incl. db). Then force-recreate ONLY
#    the app so it always re-reads the current .env — a bare recreate reuses the old
#    container's env, which silently stranded CORS/captcha changes (env-drift). DB is
#    left untouched (no --force-recreate on it) to avoid a needless data-layer restart.
Log "recover app not healthy (code=$code); running compose up + app force-recreate"
$out = & cmd /c "$compose up -d 2>&1"
Log ("compose.up " + ($out -join " | "))
$outApp = & cmd /c "$compose up -d --force-recreate --no-deps app 2>&1"
Log ("compose.up.app " + ($outApp -join " | "))

# 4) Re-check after a short boot wait.
Start-Sleep -Seconds 12
try { $code2 = (Invoke-WebRequest -Uri $health -UseBasicParsing -TimeoutSec 8).StatusCode } catch { $code2 = 0 }
if ($code2 -eq 200) { Log "recover.ok app healthy after restart" }
else                { Log "recover.pending app still not 200 (code=$code2); will retry next cycle" }

# 5) DEAD-MAN'S SWITCH for the autonomous bug-fix watchdog.
# The autofix loop has its own internal timeouts, but a hang INSIDE the loop (a
# wedged Stop-Job/Wait-Job, blocked I/O) can freeze it with no exception, so its
# own crash-relaunch never fires and it sits dead-alive holding the mutex - nothing
# gets fixed. This watchdog runs every 5 min in a SEPARATE task, so it survives that
# freeze and is the right place to enforce "stuck -> kill everything -> restart".
# Rule: if status.json's heartbeat is older than $autofixStaleMin, OR the recorded
# PID is gone, kill the autofix process tree and relaunch a fresh one. The autofix
# single-instance mutex makes a redundant launch harmless (a live one just exits).
$autofixStaleMin = 12  # > the 480s (8min) fixer deadline + slack: the heartbeat is
                       # frozen for the whole fixer call, so this MUST exceed it or a
                       # legit long fix gets killed as a false "wedged loop".
$autofixScript   = Join-Path $repo "auto-fix-watchdog.ps1"
$statusFile      = Join-Path $dataRoot "logs\watchdog-status.json"

function Restart-Autofix([string]$why) {
    Log "autofix.restart $why"
    # Kill any existing autofix process tree(s): the recorded PID and any stray
    # powershell running the script. /T /F takes the children (claude/node/mvn) too.
    try {
        $st = $null
        if (Test-Path $statusFile) { $st = Get-Content $statusFile -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json }
        if ($st -and $st.pid) { & cmd /c "taskkill /PID $($st.pid) /T /F" 2>$null | Out-Null }
    } catch { }
    try {
        Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -match 'auto-fix-watchdog\.ps1' } |
            ForEach-Object { & cmd /c "taskkill /PID $($_.ProcessId) /T /F" 2>$null | Out-Null }
    } catch { }
    Start-Sleep -Seconds 3   # let the OS reap the tree so the mutex frees
    # Relaunch detached + hidden, same way the task and the self-relaunch do.
    try {
        Start-Process -FilePath "powershell.exe" `
            -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File', $autofixScript) `
            -WindowStyle Hidden | Out-Null
        Log "autofix.relaunched fresh process started"
    } catch { Log ("autofix.relaunch_failed " + $_.Exception.Message) }
}

if (-not (Test-Path $autofixScript)) {
    # autofix not deployed here; skip the dead-man's switch
} elseif (-not (Test-Path $statusFile)) {
    Restart-Autofix "no status.json - autofix never wrote a heartbeat or is freshly dead"
} else {
    try {
        $st = Get-Content $statusFile -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
        $alive = $false
        if ($st.pid) { $alive = [bool](Get-Process -Id $st.pid -ErrorAction SilentlyContinue) }
        $hbAgeMin = [double]::PositiveInfinity
        if ($st.heartbeatUtc) {
            $hb = [datetime]::Parse($st.heartbeatUtc, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind)
            $hbAgeMin = ((Get-Date).ToUniversalTime() - $hb.ToUniversalTime()).TotalMinutes
        }
        if (-not $alive) {
            Restart-Autofix ("recorded pid {0} is gone (state={1}, hbAge={2:N1}min)" -f $st.pid, $st.state, $hbAgeMin)
        } elseif ($hbAgeMin -gt $autofixStaleMin) {
            Restart-Autofix ("heartbeat stale {0:N1}min > {1}min (state={2}, pid={3}) - loop is wedged" -f $hbAgeMin, $autofixStaleMin, $st.state, $st.pid)
        } elseif ((Get-Date).Minute % 30 -eq 0) {
            Log ("autofix.ok alive pid={0} state={1} hbAge={2:N1}min" -f $st.pid, $st.state, $hbAgeMin)
        }
    } catch {
        Log ("autofix.check_error " + $_.Exception.Message + " - restarting to be safe")
        Restart-Autofix "status.json unreadable/corrupt"
    }
}
