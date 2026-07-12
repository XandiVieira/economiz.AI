# One-time data migration: local Docker Postgres -> Render managed Postgres.
#
# Direction is LOCAL -> RENDER (the reverse of the old migrate-from-render.ps1).
# Moves all rows (accounts, receipts, the ~910k-row EAN catalog, etc.) so the
# Render DB starts as a copy of the current dev box instead of empty.
#
# PREREQUISITE: the schema must already exist on Render. Deploy the app to Render
# ONCE first so Flyway creates every table, THEN run this (it loads DATA ONLY and
# will not fight Flyway's schema).
#
# USAGE (PowerShell, from the repo root):
#   $env:RENDER_PG = "postgresql://USER:PASS@HOST:PORT/economizai"   # from Render → DB → "External Connection"
#   powershell -ExecutionPolicy Bypass -File .\migrate-to-render.ps1
#
# Strategy:
#   1. Capture per-table row counts from BOTH sides (before).
#   2. pg_dump DATA ONLY from local (no schema — Render already has it via Flyway).
#      --disable-triggers so FK order doesn't matter during load.
#   3. Restore into Render inside a single transaction.
#   4. Re-compare row counts to PROVE nothing was lost.
#
# Safe: reads from local, writes only to RENDER. Uses the pg client tools INSIDE
# the economizai-db container (guaranteed version match, no local psql needed).
# Credentials come from $env:RENDER_PG so they never touch disk.
# ---------------------------------------------------------------------------
$ErrorActionPreference = "Stop"

if (-not $env:RENDER_PG) {
    Write-Host "ERROR: set `$env:RENDER_PG first (the Render External Connection string, postgresql://...)."
    exit 1
}
$render = $env:RENDER_PG
$dumpFile = "/tmp/economizai_data.sql"     # path INSIDE the container

function LocalPsql($sql) {
    docker exec economizai-db psql -U economizai -d economizai -tAc $sql
}
function RenderPsql($sql) {
    docker exec economizai-db psql "$render" -tAc $sql
}

Write-Host "=== 1) row counts BEFORE ==="
$countSql = @"
SELECT relname || '=' || n_live_tup
FROM pg_stat_user_tables WHERE n_live_tup > 0 ORDER BY relname;
"@
Write-Host "--- LOCAL ---"
$localBefore = LocalPsql $countSql
$localBefore | ForEach-Object { Write-Host "  $_" }
Write-Host "--- RENDER (should be near-empty: only Flyway-seeded rows) ---"
$renderBefore = RenderPsql $countSql
$renderBefore | ForEach-Object { Write-Host "  $_" }

Write-Host ""
Write-Host "=== 2) pg_dump DATA-ONLY from local -> $dumpFile ==="
# --data-only: schema stays as Flyway created it on Render.
# --disable-triggers: load ignores FK order (runs as table owner).
# --no-owner/--no-privileges: Render user differs from local 'economizai'.
docker exec economizai-db pg_dump -U economizai -d economizai `
    --data-only --disable-triggers --no-owner --no-privileges `
    --exclude-table=flyway_schema_history `
    -f $dumpFile
$size = docker exec economizai-db sh -c "wc -c < $dumpFile"
Write-Host "dump written ($size bytes)"

Write-Host ""
Write-Host "=== 3) restore into Render (single transaction) ==="
# --single-transaction: all-or-nothing. -v ON_ERROR_STOP so a failure aborts.
docker exec economizai-db sh -c "psql '$render' --single-transaction -v ON_ERROR_STOP=1 -f $dumpFile" 2>&1 |
    Select-Object -Last 15
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: restore failed (rolled back). Render DB unchanged. Fix and re-run."
    exit 1
}

Write-Host ""
Write-Host "=== 4) row counts AFTER (RENDER) — compare to LOCAL above ==="
$renderAfter = RenderPsql $countSql
$renderAfter | ForEach-Object { Write-Host "  $_" }

Write-Host ""
Write-Host "=== VERIFY: tables that DON'T match local ==="
$localMap = @{}
$localBefore | ForEach-Object { $kv = $_ -split '='; if ($kv.Count -eq 2) { $localMap[$kv[0]] = $kv[1] } }
$mismatch = 0
$renderAfter | ForEach-Object {
    $kv = $_ -split '='
    if ($kv.Count -eq 2) {
        $t = $kv[0]; $r = $kv[1]
        $l = $localMap[$t]
        if ($l -ne $r) { Write-Host "  MISMATCH $t : local=$l render=$r"; $mismatch++ }
    }
}
if ($mismatch -eq 0) { Write-Host "  all migrated tables match local row counts. Migration OK." }
else { Write-Host "  $mismatch table(s) differ — investigate before trusting the Render DB." }

# cleanup the dump inside the container
docker exec economizai-db rm -f $dumpFile
Write-Host "done."
