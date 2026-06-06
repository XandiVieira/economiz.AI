# Run in an Administrator PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup-autostart.ps1
#
# Makes the economizai dev server come back automatically after a reboot.
#
# Two layers:
#   1. Containers already have restart=unless-stopped (Docker brings them back
#      once the engine is up).
#   2. This script ensures the Docker ENGINE comes up after a reboot via a
#      Scheduled Task that starts Docker Desktop at logon of this user.
#
# NOTE: Docker Desktop needs a user session (its WSL backend won't run with
# nobody logged in). So this triggers at THIS user's logon. To make it fully
# unattended (survive a 3am reboot with no human present), you must ALSO enable
# auto-login for this account -- see the printed instructions at the end.

$taskName = "economizai - start Docker engine"
$dockerExe = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
$user = "$env:USERDOMAIN\$env:USERNAME"

if (-not (Test-Path $dockerExe)) { Write-Host "Docker Desktop.exe not found at $dockerExe" -ForegroundColor Red; exit 1 }

# Remove any prior version of this task.
Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false

$action  = New-ScheduledTaskAction -Execute $dockerExe
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
# Settings: run on AC or battery, don't stop on idle, restart if it fails.
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal -Description "Starts Docker Desktop so the economizai dev server recovers after reboot." | Out-Null

Write-Host "`nScheduled task created: '$taskName'" -ForegroundColor Green
Get-ScheduledTask -TaskName $taskName | Select-Object TaskName, State | Format-List

Write-Host "=== To make it FULLY unattended (recover from a reboot with NO login) ===" -ForegroundColor Cyan
Write-Host "Enable auto-login for this account so the user session starts itself:"
Write-Host "  1. Win+R -> netplwiz -> Enter"
Write-Host "  2. Uncheck 'Users must enter a user name and password to use this computer'"
Write-Host "  3. Apply -> type this account's password twice -> OK"
Write-Host ""
Write-Host "Without auto-login: after a reboot the server comes up as soon as YOU log in." -ForegroundColor Yellow
