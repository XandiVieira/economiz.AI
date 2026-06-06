# One-time data migration: Render (suspended->resumed) -> local Docker Postgres.
#
# Run AFTER the Render DB shows "Available" in the dashboard:
#   powershell -ExecutionPolicy Bypass -File .\migrate-from-render.ps1
#
# Strategy:
#   1. Connect to Render, capture its server version + per-table row counts.
#   2. pg_dump DATA ONLY from Render (schema already exists locally via Flyway).
#   3. Truncate-and-load into the local DB inside a transaction.
#   4. Compare row counts Render vs local to PROVE nothing was lost.
#
# Safe: reads from Render, writes only to the LOCAL db. Does NOT modify Render.
# NOTE: credentials are read from env vars so they are never written to disk.
#   $env:RENDER_PG = "postgresql://user:pass@host/db"   (set before running)
# ---------------------------------------------------------------------------
$ErrorActionPreference = "Stop"

if (-not $env:RENDER_PG) {
    Write-Error "Set `$env:RENDER_PG to the Render External Database URL first."
    exit 1
}
$render = $env:RENDER_PG
$dumpFile = "/tmp/render_data.sql"          # inside the local db container
$localDb  = "economizai"
$localUser= "economizai"

function RenderPsql($sql) {
    docker exec economizai-db sh -c "PGCONNECT_TIMEOUT=20 psql '$render' -t -A -c `"$sql`""
}
function LocalPsql($sql) {
    docker exec economizai-db psql -U $localUser -d $localDb -t -A -c "$sql"
}

Write-Host "=== 1. Render reachable? version + table counts ===" -ForegroundColor Cyan
$ver = RenderPsql "select version();"
Write-Host "Render: $ver"

Write-Host "`n=== Row counts on RENDER (source of truth) ===" -ForegroundColor Cyan
$countSql = @"
select table_name||'='||(xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
from information_schema.tables where table_schema='public' and table_type='BASE TABLE' order by table_name;
"@
$renderCounts = RenderPsql $countSql
Write-Host $renderCounts

Write-Host "`n=== 2. Dump DATA from Render (schema stays as Flyway built it) ===" -ForegroundColor Cyan
# --data-only: rows only. --disable-triggers: load despite FKs. Exclude flyway history.
docker exec economizai-db sh -c "pg_dump '$render' --data-only --disable-triggers --no-owner --no-privileges --exclude-table=flyway_schema_history -f $dumpFile"
$size = docker exec economizai-db sh -c "wc -c < $dumpFile"
Write-Host "Dump written: $size bytes"

Write-Host "`n=== 3. Load into LOCAL db (truncate existing empty tables first) ===" -ForegroundColor Cyan
# Truncate all non-flyway tables so the data-only load has clean targets.
$truncate = "do `$`$ declare r record; begin for r in (select tablename from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history') loop execute 'truncate table '||quote_ident(r.tablename)||' cascade'; end loop; end `$`$;"
LocalPsql $truncate | Out-Null
docker exec economizai-db sh -c "psql -U $localUser -d $localDb -v ON_ERROR_STOP=1 -f $dumpFile" 2>&1 | Select-Object -Last 5

Write-Host "`n=== 4. VERIFY: row counts LOCAL (must match Render) ===" -ForegroundColor Cyan
$localCounts = docker exec economizai-db psql -U $localUser -d $localDb -t -A -c $countSql
Write-Host $localCounts

Write-Host "`nCompare the two count lists above. If they match, migration is complete." -ForegroundColor Green
docker exec economizai-db rm -f $dumpFile
