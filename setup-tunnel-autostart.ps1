# Run in an Administrator PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\setup-tunnel-autostart.ps1
#
# Registers a Scheduled Task that runs start-tunnel.ps1 at logon and keeps the
# Cloudflare tunnel alive (restarts it if it exits). start-tunnel.ps1 also
# auto-syncs CORS and publishes the live URL to current-tunnel-url.txt.
# ---------------------------------------------------------------------------
$repo   = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$user   = "$env:USERDOMAIN\$env:USERNAME"
$task   = "economizai - cloudflare tunnel"
$script = Join-Path $repo "start-tunnel.ps1"

Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue | Unregister-ScheduledTask -Confirm:$false

$action    = New-ScheduledTaskAction -Execute "powershell.exe" -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script + '"')
$trigger   = New-ScheduledTaskTrigger -AtLogOn -User $user
# Restart the tunnel if start-tunnel.ps1 ever exits (cloudflared dropped).
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest

Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description "Keep the economizai Cloudflare tunnel up; auto-sync CORS + publish URL." | Out-Null

Write-Host "Tunnel auto-start task registered: '$task'" -ForegroundColor Green
Get-ScheduledTask -TaskName $task | Select-Object TaskName, State | Format-List
Write-Host "Live URL is always in: $repo\current-tunnel-url.txt" -ForegroundColor Cyan
