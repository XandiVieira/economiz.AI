# Run in an Administrator PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup-backup-schedule.ps1
#
# Schedules backup-db.ps1 to run daily at 03:00 (and on startup if a run was
# missed while the machine was off).
# ---------------------------------------------------------------------------
$repo   = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$user   = "$env:USERDOMAIN\$env:USERNAME"
$task   = "economizai - daily db backup"
$script = Join-Path $repo "backup-db.ps1"

Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false

$action    = New-ScheduledTaskAction -Execute "powershell.exe" -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script + '"')
$trigger   = New-ScheduledTaskTrigger -Daily -At 3am
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest

Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description "Daily pg_dump of the economizai DB to db-backups\ (OneDrive-synced)." | Out-Null

Write-Host "Daily backup scheduled (03:00)." -ForegroundColor Green
Get-ScheduledTask -TaskName $task | Select-Object TaskName, State | Format-List
