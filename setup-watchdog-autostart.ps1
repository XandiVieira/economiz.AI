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

$action  = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
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
