# Run in an Administrator PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\make-always-on.ps1
#
# Turns this Windows box into a self-recovering, never-sleeping dev server.
# Idempotent: safe to run repeatedly. ASCII-only, no backtick line-continuations
# (Windows PowerShell 5.1 + non-UTF8 console choke on those).
# ---------------------------------------------------------------------------

$ErrorActionPreference = "Continue"
function Section($t){ Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }

$repo = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$user = "$env:USERDOMAIN\$env:USERNAME"

# ---------------------------------------------------------------------------
# LAYER 1 - Never sleep / hibernate / turn off disk (AC and battery).
# ---------------------------------------------------------------------------
Section "1. Power: never sleep / hibernate / spin down disk"
powercfg /change standby-timeout-ac 0
powercfg /change standby-timeout-dc 0
powercfg /change hibernate-timeout-ac 0
powercfg /change hibernate-timeout-dc 0
powercfg /change disk-timeout-ac 0
powercfg /change disk-timeout-dc 0
powercfg /change monitor-timeout-ac 15
powercfg /change monitor-timeout-dc 15
powercfg /hibernate off
# Lid close = do nothing (laptop).
powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0
powercfg /setdcvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0
powercfg /setactive SCHEME_CURRENT
Write-Host "Power: sleep/hibernate/disk = never; lid close = stay on." -ForegroundColor Green

# ---------------------------------------------------------------------------
# LAYER 2 - Docker Desktop auto-starts at logon and restarts on failure.
# ---------------------------------------------------------------------------
Section "2. Docker Desktop auto-start at logon + restart on failure"
$taskName  = "economizai - start Docker engine"
$dockerExe = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
if (Test-Path $dockerExe) {
    Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false
    $action    = New-ScheduledTaskAction -Execute $dockerExe
    $trigger   = New-ScheduledTaskTrigger -AtLogOn -User $user
    $settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1)
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description "Start Docker Desktop so the economizai dev server recovers after reboot." | Out-Null
    Write-Host "Docker Desktop will start at logon (restart up to 5x on failure)." -ForegroundColor Green
} else {
    Write-Host "Docker Desktop.exe not found - skipped." -ForegroundColor Red
}

# ---------------------------------------------------------------------------
# LAYER 3 - Containers self-heal (already restart:unless-stopped in compose).
# ---------------------------------------------------------------------------
Section "3. Containers restart policy = unless-stopped"
docker update --restart unless-stopped economizai-app economizai-db 2>&1 | Out-Host
Write-Host "Containers will auto-restart unless you explicitly stop them." -ForegroundColor Green

# ---------------------------------------------------------------------------
# LAYER 4 - Watchdog: bring the stack up at startup and every 5 min.
# ---------------------------------------------------------------------------
Section "4. Watchdog: ensure stack is up at startup + every 5 min"
$watchTask   = "economizai - stack watchdog"
$watchScript = Join-Path $repo "stack-watchdog.ps1"
Get-ScheduledTask -TaskName $watchTask -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false
$wAction    = New-ScheduledTaskAction -Execute "powershell.exe" -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $watchScript + '"')
$wTrig1     = New-ScheduledTaskTrigger -AtStartup
$wTrig2     = New-ScheduledTaskTrigger -Once -At ((Get-Date).Date.AddMinutes(1)) -RepetitionInterval (New-TimeSpan -Minutes 5)
$wSettings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$wPrincipal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
Register-ScheduledTask -TaskName $watchTask -Action $wAction -Trigger @($wTrig1, $wTrig2) -Settings $wSettings -Principal $wPrincipal -Description "Bring the economizai stack up at startup and keep it up (checks every 5 min)." | Out-Null
Write-Host "Watchdog task created (startup + every 5 min)." -ForegroundColor Green

# ---------------------------------------------------------------------------
# LAYER 5 - Notes for firmware/security steps no script can perform.
# ---------------------------------------------------------------------------
Section "5. Manual steps for true unattended uptime"
Write-Host "A) Auto-login (Docker needs a user session): Win+R -> netplwiz -> uncheck the password box." -ForegroundColor Yellow
Write-Host "B) BIOS: 'Restore on AC Power Loss = ON' so it powers up after an outage." -ForegroundColor Yellow
Write-Host "C) Prefer WIRED ETHERNET to avoid mesh-roam IP loss." -ForegroundColor Yellow

Write-Host ""
Write-Host "DONE. Layers 1-4 are configured. Do A/B/C manually for full coverage." -ForegroundColor Green
