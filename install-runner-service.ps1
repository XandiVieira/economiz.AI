# Run in an ADMINISTRATOR PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\install-runner-service.ps1 -Token "NEW_TOKEN"
#
# The runner at C:\actions-runner is already registered but NOT running as a
# service. This re-configures it with --runasservice (needs admin) so it starts
# on boot. Get a FRESH token (the old one expires in ~1h) from:
#   https://github.com/XandiVieira/economiz.AI/settings/actions/runners
#   -> New self-hosted runner -> Windows -> copy the --token value.
# ---------------------------------------------------------------------------
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$RepoUrl = "https://github.com/XandiVieira/economiz.AI",
    [string]$Label   = "economizai-dev"
)
$ErrorActionPreference = "Stop"
Set-Location "C:\actions-runner"

# Service runs as THIS user (Xandi) so it has the rights it needs and matches
# Docker's setup. config.cmd wants the account as DOMAIN\user.
$account = "$env:COMPUTERNAME\$env:USERNAME"
Write-Host "Service will run as: $account" -ForegroundColor Cyan
Write-Host "Enter the Windows password for $account (input hidden):" -ForegroundColor Cyan
$sec = Read-Host -AsSecureString
$pwd = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))

# Remove the existing registration first, ignore if already clean.
Write-Host "Removing prior runner registration..." -ForegroundColor Cyan
& ".\config.cmd" remove --token $Token 2>&1 | Out-Host

# Re-configure as a Windows service running as the user account.
Write-Host "Configuring runner as a service..." -ForegroundColor Cyan
& ".\config.cmd" --url $RepoUrl --token $Token --name "economizai-dev-box" --labels $Label --unattended --replace --runasservice --windowslogonaccount $account --windowslogonpassword $pwd
$pwd = $null   # clear from memory

Start-Sleep -Seconds 3
Write-Host "`nDone." -ForegroundColor Green
Get-Service -Name "actions.runner.*" -ErrorAction SilentlyContinue | Select-Object Name, Status, StartType | Format-List
Write-Host "Verify 'Idle' at: $RepoUrl/settings/actions/runners" -ForegroundColor Green
