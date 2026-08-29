# Run in an Administrator PowerShell:
#   powershell -ExecutionPolicy Bypass -File .\set-static-ip.ps1
#
# Pins the Wi-Fi adapter to a static IP so the dev-server LAN address never
# changes. Values match the current DHCP lease, so CORS / phone URLs stay valid.
#
# TO REVERT (back to automatic IP):
#   netsh interface ip set address name="Wi-Fi" source=dhcp
#   netsh interface ip set dns     name="Wi-Fi" source=dhcp

$ifAlias = "Wi-Fi"
$ip      = "192.168.68.108"
$prefix  = 24
$gateway = "192.168.68.1"
$dns     = "192.168.68.1","8.8.8.8"   # gateway first, Google DNS as backup

Write-Host "Setting static IP $ip/$prefix on '$ifAlias' (gw $gateway)..." -ForegroundColor Cyan

# Remove existing IPv4 address + default route on this interface, then set static.
Get-NetIPAddress -InterfaceAlias $ifAlias -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Remove-NetIPAddress -Confirm:$false -ErrorAction SilentlyContinue
Get-NetRoute -InterfaceAlias $ifAlias -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
    Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue

New-NetIPAddress -InterfaceAlias $ifAlias -IPAddress $ip -PrefixLength $prefix `
    -DefaultGateway $gateway -ErrorAction Stop | Out-Null

Set-DnsClientServerAddress -InterfaceAlias $ifAlias -ServerAddresses $dns -ErrorAction Stop

Start-Sleep -Seconds 3

Write-Host "`n=== New config ===" -ForegroundColor Green
Get-NetIPConfiguration -InterfaceAlias $ifAlias |
    Select-Object InterfaceAlias,
        @{n='IPv4';e={$_.IPv4Address.IPAddress}},
        @{n='Gateway';e={$_.IPv4DefaultGateway.NextHop}},
        @{n='DNS';e={($_.DNSServer | ? AddressFamily -eq 2).ServerAddresses -join ', '}} |
    Format-List

Write-Host "=== Connectivity test (ping gateway + internet) ===" -ForegroundColor Green
$gwOk  = Test-Connection -ComputerName $gateway -Count 2 -Quiet
$netOk = Test-Connection -ComputerName "8.8.8.8"  -Count 2 -Quiet
Write-Host ("Gateway reachable : {0}" -f $gwOk)
Write-Host ("Internet reachable: {0}" -f $netOk)

if (-not $gwOk -or -not $netOk) {
    Write-Host "`nWARNING: connectivity lost. Revert with:" -ForegroundColor Red
    Write-Host '  netsh interface ip set address name="Wi-Fi" source=dhcp'
    Write-Host '  netsh interface ip set dns     name="Wi-Fi" source=dhcp'
} else {
    Write-Host "`nDone. Static IP $ip is set and online." -ForegroundColor Green
}
