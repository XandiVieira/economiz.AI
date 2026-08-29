# Flyway duplicate-version guard + auto-renumber.
# ---------------------------------------------------------------------------
# Two migrations that claim the same Vnn make the app abort on every startup
# (FlywayException: Found more than one migration with version N) -> crash loop
# -> 502. This catches it BEFORE it ships and auto-renumbers the colliding file.
#
# WHICH file gets renumbered: the NEWCOMER - the one NOT yet present on
# origin/<branch>. A version already pushed (and very likely already applied to
# the live DB's flyway_schema_history) must NEVER be renumbered. If BOTH files
# are new (neither on origin) we bump the one added later by git, falling back to
# lexical order so the choice is deterministic.
#
# Exit codes:
#   0  no collision (or collision auto-resolved and -Fix was set)
#   1  collision found and NOT fixed (caller should block) - only in -Check mode
#   2  internal error
#
# Modes:
#   -Check          report + exit 1 on an unresolved collision (no file changes)
#   -Fix            auto-renumber the newcomer to the next free Vnn (git mv)
#   -Branch <name>  origin branch to treat as "already shipped" (default development)
# ---------------------------------------------------------------------------
param(
    [switch]$Check,
    [switch]$Fix,
    [string]$Branch = "development"
)
# Continue, not Stop: native git calls write progress/errors to stderr that
# PowerShell would otherwise promote to terminating NativeCommandErrors. We guard
# every git call by its exit code instead, so noisy-but-successful calls (e.g.
# `git cat-file -e` on an absent path) must not abort the script.
$ErrorActionPreference = "Continue"
if (-not $Check -and -not $Fix) { $Check = $true }   # default: report-only

$repoRoot = (& git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) { Write-Error "not a git repo"; exit 2 }
$migDir = Join-Path $repoRoot "src/main/resources/db/migration"
if (-not (Test-Path $migDir)) { exit 0 }   # nothing to check

# Parse "V<num>__<desc>.sql" -> @{ Version; File; Name }. Ignore repeatable (R__)
# and undo (U__) migrations; only versioned V migrations collide on Vnn.
function Get-Migrations {
    Get-ChildItem -Path $migDir -Filter "V*.sql" -File | ForEach-Object {
        if ($_.Name -match '^V(\d+)__') {
            [pscustomobject]@{ Version = [int]$Matches[1]; File = $_.Name; Path = $_.FullName }
        }
    }
}

$migs = @(Get-Migrations)
if ($migs.Count -eq 0) { exit 0 }

# Group by version; any group with >1 file is a collision.
$collisions = $migs | Group-Object Version | Where-Object { $_.Count -gt 1 }
if (-not $collisions) { exit 0 }

$maxVersion = ($migs | Measure-Object -Property Version -Maximum).Maximum

# Is this exact migration filename already on origin/<branch>? If so it's
# "shipped" and must not be the one we renumber.
function On-Origin([string]$fileName) {
    # Probe quietly: cat-file writes to stderr when the path is absent on origin,
    # which under -ErrorActionPreference Stop becomes a terminating NativeCommandError.
    # Suppress it and key purely on the exit code.
    & git cat-file -e "origin/${Branch}:src/main/resources/db/migration/$fileName" 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

# Make sure origin ref is current so On-Origin is accurate (best-effort, quiet).
& git fetch origin $Branch --quiet 2>$null | Out-Null

$blocked = $false
foreach ($c in $collisions) {
    $ver = $c.Name
    $files = @($c.Group)
    Write-Host ("! Flyway duplicate version V{0}:" -f $ver)
    $files | ForEach-Object { Write-Host ("    {0}" -f $_.File) }

    # Decide the newcomer to renumber. Prefer a file NOT yet on origin; if exactly
    # one is on origin, the other is the newcomer. If neither/both are on origin,
    # fall back to lexical order (deterministic) and bump the last one.
    $shipped = @($files | Where-Object { On-Origin $_.File })
    $shippedNames = @($shipped | ForEach-Object { $_.File })
    $newcomers = @($files | Where-Object { $_.File -notin $shippedNames })

    $toBump = $null
    if ($shipped.Count -ge 1 -and $newcomers.Count -ge 1) {
        # Renumber a newcomer (never a shipped one). If multiple newcomers, last lexical.
        $toBump = ($newcomers | Sort-Object File | Select-Object -Last 1)
    } elseif ($shipped.Count -eq $files.Count) {
        # BOTH already shipped to origin - we must not touch either. Block, needs human.
        Write-Host "    -> both versions already on origin/$Branch; refusing to renumber (needs human)"
        $blocked = $true
        continue
    } else {
        # None on origin (both brand new locally): bump the last lexical one.
        $toBump = ($files | Sort-Object File | Select-Object -Last 1)
    }

    $nextFree = $maxVersion + 1
    $maxVersion = $nextFree   # reserve it so a second collision doesn't reuse it
    $newName = ($toBump.File -replace '^V\d+__', ("V{0}__" -f $nextFree))
    $newPath = Join-Path $migDir $newName

    if ($Fix) {
        & git mv -- $toBump.Path $newPath 2>$null
        if ($LASTEXITCODE -ne 0) {
            # not staged-tracked yet? fall back to plain move + add
            Move-Item -LiteralPath $toBump.Path -Destination $newPath -Force
            & git add -- $newPath 2>$null | Out-Null
        }
        Write-Host ("    -> auto-renamed {0} -> {1}" -f $toBump.File, $newName)
    } else {
        Write-Host ("    -> next free version is V{0} (rename {1})" -f $nextFree, $toBump.File)
        $blocked = $true
    }
}

if ($Check -and $blocked) { exit 1 }
exit 0
