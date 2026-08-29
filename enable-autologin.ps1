# Run in an Administrator PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\enable-autologin.ps1
#
# Enables Windows auto-login for THIS local account so the box logs itself in
# after a reboot (required for Docker Desktop to start unattended).
#
# Two parts:
#   1) Flip DevicePasswordLessBuildVersion 2 -> 0 so Windows allows password
#      auto-login (Win11 hides this behind "Hello-only" by default).
#   2) Set AutoAdminLogon + DefaultUserName/Password in the Winlogon registry.
#
# SECURITY: the password is stored in the registry. Read the note at the end.
# TO DISABLE auto-login later:
#   Set-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon' AutoAdminLogon 0
#   Remove-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon' DefaultPassword -ErrorAction SilentlyContinue
# ---------------------------------------------------------------------------

# 1) Allow password-based auto-login (un-hide the option).
$plKey = "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\PasswordLess\Device"
if (Test-Path $plKey) {
    Set-ItemProperty -Path $plKey -Name DevicePasswordLessBuildVersion -Value 0
    Write-Host "PasswordLess flag set to 0 (password auto-login now permitted)." -ForegroundColor Green
}

# 2) Prompt for the password (typed securely, not echoed) and write Winlogon keys.
$win = "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon"
$me  = $env:USERNAME
Write-Host ""
Write-Host "Enter the Windows password for account '$me' (input hidden):" -ForegroundColor Cyan
$sec = Read-Host -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))

Set-ItemProperty -Path $win -Name AutoAdminLogon  -Value "1"
Set-ItemProperty -Path $win -Name DefaultUserName -Value $me
Set-ItemProperty -Path $win -Name DefaultDomainName -Value $env:COMPUTERNAME
Set-ItemProperty -Path $win -Name DefaultPassword -Value $plain
# Clear the plaintext variable from memory ASAP.
$plain = $null

Write-Host ""
Write-Host "Auto-login ENABLED for '$me'." -ForegroundColor Green
Write-Host "Verify (no reboot needed to check the keys):" -ForegroundColor Green
Get-ItemProperty $win | Select-Object AutoAdminLogon, DefaultUserName, DefaultDomainName | Format-List

Write-Host "SECURITY NOTE:" -ForegroundColor Yellow
Write-Host " - The password is stored in the registry (readable by admins/SYSTEM)." -ForegroundColor Yellow
Write-Host " - Anyone with physical access to this machine will be auto-logged-in on boot." -ForegroundColor Yellow
Write-Host " - Mitigations: enable BitLocker (encrypt the disk); keep the machine physically secure." -ForegroundColor Yellow
Write-Host ""
Write-Host "Reboot to test: the machine should log in by itself and the server come up." -ForegroundColor Cyan
