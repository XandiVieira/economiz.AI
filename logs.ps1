# economizai log helper — view & persist container logs locally.
#
# USAGE (normal PowerShell):
#   .\logs.ps1                 # tail last 200 app lines, then follow live
#   .\logs.ps1 -Errors        # only WARN/ERROR lines (live)
#   .\logs.ps1 -Grep rcpt=abc # filter live app logs by a string (req/rcpt/user/item id)
#   .\logs.ps1 -Db            # follow the database logs instead of the app
#   .\logs.ps1 -Save          # dump current logs to logs\app-YYYY-MM-DD.log (+ db), then exit
#   .\logs.ps1 -Since 30m     # show app logs from the last 30 minutes
#
# The -Save mode is also run daily by a scheduled task so you keep history on
# disk (Docker's own json-file logs rotate at 20MB x10; -Save snapshots them to
# readable dated files in logs\ , 14-day retention, OneDrive-synced).
# ---------------------------------------------------------------------------
param(
    [switch]$Errors,
    [string]$Grep,
    [switch]$Db,
    [switch]$Save,
    [string]$Since,
    [int]$Tail = 200
)
$ErrorActionPreference = "Continue"
$repo = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
Set-Location $repo
& docker context use desktop-linux *> $null

$container = if ($Db) { "economizai-db" } else { "economizai-app" }

if ($Save) {
    $logDir = Join-Path $repo "logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $date = Get-Date -Format "yyyy-MM-dd"
    foreach ($c in @("economizai-app", "economizai-db")) {
        $name = $c -replace "economizai-", ""
        $out = Join-Path $logDir "$name-$date.log"
        # append today's logs (since midnight) so repeated runs in a day accumulate
        & cmd /c "docker logs --since `"${date}T00:00:00`" $c > `"$out`" 2>&1"
        Write-Host "saved $c -> $out"
    }
    # retention: delete saved logs older than 14 days
    Get-ChildItem $logDir -Filter "*.log" | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) } |
        ForEach-Object { Remove-Item $_.FullName -Force; Write-Host "pruned $($_.Name)" }
    return
}

# Build the docker logs args.
$since = if ($Since) { "--since $Since" } else { "--tail $Tail" }

if ($Errors) {
    Write-Host "=== $container : WARN/ERROR (live) ===" -ForegroundColor Yellow
    & cmd /c "docker logs -f $since $container 2>&1" | Select-String -Pattern "WARN|ERROR|Exception|FAIL" -CaseSensitive:$false
}
elseif ($Grep) {
    Write-Host "=== $container : lines matching '$Grep' (live) ===" -ForegroundColor Cyan
    & cmd /c "docker logs -f $since $container 2>&1" | Select-String -Pattern $Grep -SimpleMatch
}
else {
    Write-Host "=== $container : last $Tail lines, then following (Ctrl+C to stop) ===" -ForegroundColor Green
    & cmd /c "docker logs -f $since $container 2>&1"
}
