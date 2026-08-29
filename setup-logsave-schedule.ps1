# Run in an Administrator PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup-logsave-schedule.ps1
#
# Schedules logs.ps1 -Save to run daily at 02:55 (just before the 03:00 DB
# backup), snapshotting container logs to dated files in
# C:\economizai-data\logs (14-day retention). Docker's own json-file logs
# rotate (20MBx10 app / 10MBx3 db); this keeps readable history for debugging.
# ---------------------------------------------------------------------------
$repo   = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$user   = "$env:USERDOMAIN\$env:USERNAME"
$task   = "economizai - daily log save"
$script = Join-Path $repo "logs.ps1"

Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false

$action    = New-ScheduledTaskAction -Execute "powershell.exe" -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script + '" -Save')
$trigger   = New-ScheduledTaskTrigger -Daily -At 2:55am
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest

Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description "Daily snapshot of economizai container logs to C:\economizai-data\logs (14-day retention)." | Out-Null

Write-Host "Daily log-save scheduled (02:55)." -ForegroundColor Green
Get-ScheduledTask -TaskName $task | Select-Object TaskName, State | Format-List
