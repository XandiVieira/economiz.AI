# Run in an Administrator PowerShell:  powershell -ExecutionPolicy Bypass -File .\setup-firewall.ps1
# Creates the correct inbound rule for the economizai dev server (TCP 8080, Private profile).

$name = "economizai dev server 8080"

# Remove any prior (possibly malformed) rules with this name.
Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule

# Create the correct rule.
New-NetFirewallRule `
    -DisplayName $name `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort 8080 `
    -Profile Private | Out-Null

# Verify.
$r  = Get-NetFirewallRule -DisplayName $name
$pf = $r | Get-NetFirewallPortFilter
[PSCustomObject]@{
    Enabled   = $r.Enabled
    Direction = $r.Direction
    Action    = $r.Action
    Profile   = $r.Profile
    Protocol  = $pf.Protocol
    LocalPort = $pf.LocalPort
} | Format-List

Write-Host "`nDone. Expect: Protocol=TCP, LocalPort=8080, Profile=Private." -ForegroundColor Green
