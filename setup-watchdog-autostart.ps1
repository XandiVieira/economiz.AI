# Registers the autonomous bug-fix watchdog as a logon Scheduled Task so it
# survives reboots and starts unattended. Mirrors the other economizai infra
# tasks (run as the logged-in user, highest privileges, hidden, auto-restart).
#
# RUN ONCE in an ADMINISTRATOR PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup-watchdog-autostart.ps1
#
# A 3-minute logon delay lets the Docker engine + app come up healthy first
# (the watchdog needs the app container running to read its logs).
# ---------------------------------------------------------------------------
$ErrorActionPreference = "Stop"
$taskName = "economizai - autonomous bug-fix watchdog"
$script   = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI\auto-fix-watchdog.ps1"

if (-not (Test-Path $script)) { Write-Host "watchdog script not found at $script" -ForegroundColor Red; exit 1 }

# Launch via a detached wrapper: `cmd /c start "" /b powershell ...` spawns the
# watchdog as an INDEPENDENT process and lets the task's own action exit at once.
# Running powershell.exe -WindowStyle Hidden directly as the action made the Task
# Scheduler kill the loop within ~5s (it treated the hidden process as the action
# and terminated it), which RestartCount then re-triggered — the phantom "restart
# loop". Detaching fixes that: the scheduler sees the cmd action complete cleanly
# while the real loop keeps running on its own.
$inner   = "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"`"$script`"`""
$action  = New-ScheduledTaskAction -Execute "cmd.exe" `
    -Argument "/c start `"economizai-watchdog`" /b $inner"
$trigger = New-ScheduledTaskTrigger -AtLogOn
$trigger.Delay = "PT3M"
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 5) `
    -ExecutionTimeLimit (New-TimeSpan -Hours 0)

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null

Write-Host "Registered '$taskName' (logon + 3min delay, auto-restart x3)." -ForegroundColor Green
Write-Host "It will start the watchdog automatically on every logon/reboot." -ForegroundColor Green
