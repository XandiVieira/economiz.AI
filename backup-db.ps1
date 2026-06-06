# Daily backup of the local economizai Postgres DB.
#
# Manual:  powershell -ExecutionPolicy Bypass -File .\backup-db.ps1
# Auto:    run by the "economizai - daily db backup" Scheduled Task.
#
# Writes a compressed pg_dump to db-backups\ (kept in OneDrive so it's also
# synced off-machine -> survives a disk failure). Keeps the last 14 days,
# plus the 1st-of-month dumps for longer history. Logs to db-backup.log.
# ---------------------------------------------------------------------------
$ErrorActionPreference = "Stop"
$repo    = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$bkpDir  = Join-Path $repo "db-backups"
$logFile = Join-Path $repo "db-backup.log"
$db      = "economizai"
$dbUser  = "economizai"
$keepDays = 14

function Log($m){ Add-Content -Path $logFile -Value ("{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) }

New-Item -ItemType Directory -Force -Path $bkpDir | Out-Null

# Timestamp comes from the OS (this script runs on the machine, not in the
# restricted workflow sandbox, so Get-Date is fine here).
$stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$inContainer = "/tmp/economizai_$stamp.dump"
$outFile     = Join-Path $bkpDir "economizai_$stamp.dump"

try {
    # 1) Dump inside the container (custom format = compressed + restorable).
    docker exec economizai-db sh -c "pg_dump -U $dbUser -d $db -Fc -f $inContainer"

    # 2) Copy the dump out to the host backup dir.
    docker cp "economizai-db:$inContainer" $outFile
    docker exec economizai-db sh -c "rm -f $inContainer"

    $size = (Get-Item $outFile).Length
    if ($size -lt 1024) { throw "backup file suspiciously small ($size bytes)" }
    Log "ok backup written: $outFile ($([math]::Round($size/1KB)) KB)"

    # 3) Retention: delete dumps older than $keepDays, EXCEPT 1st-of-month ones.
    Get-ChildItem $bkpDir -Filter "economizai_*.dump" | Where-Object {
        $_.LastWriteTime -lt (Get-Date).AddDays(-$keepDays) -and
        $_.Name -notmatch '_01_'   # keep monthly anchors (day 01)
    } | ForEach-Object { Remove-Item $_.FullName -Force; Log "pruned old backup: $($_.Name)" }

    Write-Host "Backup OK: $outFile" -ForegroundColor Green
}
catch {
    Log "ERROR backup failed: $($_.Exception.Message)"
    Write-Host "Backup FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
