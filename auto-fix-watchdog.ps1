# economizai AUTONOMOUS bug-fix watchdog.
# ---------------------------------------------------------------------------
# Watches live app logs. On a NEW error signature, invokes headless Claude to
# diagnose + fix, gates on `mvnw test`, commits/pushes (triggering auto-deploy),
# verifies health, and AUTO-REVERTS + keeps running if the deploy goes unhealthy.
# Every action is appended to AUTONOMOUS_FIXES.md.
#
# Autonomy: FULL (no human gate) - enabled by the repo owner 2026-06-07 for the
# self-hosted DEV server only. See AUTONOMOUS_FIXES.md header.
#
# Run:    powershell -ExecutionPolicy Bypass -File .\auto-fix-watchdog.ps1
# DryRun: powershell -ExecutionPolicy Bypass -File .\auto-fix-watchdog.ps1 -DryRun
#         (diagnose + build, but NEVER push/deploy - for safe testing)
# ---------------------------------------------------------------------------
param(
    [switch]$DryRun,
    [int]$MaxFixesPerHour = 3,
    [int]$PollSeconds     = 20,
    [string]$Branch       = "development"
)

$ErrorActionPreference = "Stop"
$repo     = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$ledger   = Join-Path $repo "AUTONOMOUS_FIXES.md"
$logFile  = Join-Path $repo "auto-fix-watchdog.log"
$health   = "http://localhost:8080/actuator/health"
$javaHome = "C:\Users\Xandi\.jdks\openjdk-21"
$container = "economizai-app"
$MARKER   = "AUTONOMOUS ENTRIES BELOW"

Set-Location $repo
$env:JAVA_HOME = $javaHome

function Log([string]$msg) {
    $line = ("{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg)
    Add-Content -Path $logFile -Value $line
    Write-Host $line
}

# Insert a finished markdown block into the ledger, right after the marker line.
function Ledger([string]$block) {
    $content = Get-Content $ledger -Raw
    $content = $content -replace "(?s)_No autonomous fixes yet.*?detected bug\._", ""
    if ($content.IndexOf($MARKER) -ge 0) {
        $insertAt = $content.IndexOf("`n", $content.IndexOf($MARKER))
        $head = $content.Substring(0, $insertAt + 1)
        $tail = $content.Substring($insertAt + 1)
        Set-Content -Path $ledger -Value ($head + "`r`n" + $block.TrimEnd() + "`r`n" + $tail) -Encoding UTF8
    } else {
        Add-Content -Path $ledger -Value ("`r`n`r`n" + $block)
    }
}

function Stamp { return (Get-Date -Format "yyyy-MM-dd HH:mm:ss") }

# Reduce a log line to a stable signature so we don't fix the same bug twice.
function Signature([string]$line) {
    $s = $line -replace '\d{4}-\d{2}-\d{2} [\d:.]+', '' `
               -replace 'req=[a-f0-9]+', '' -replace 'user=\S*', '' `
               -replace 'rcpt=\S*', '' -replace 'item=\S*', '' `
               -replace '"[^"]*"', 'X' -replace '\d+', 'N'
    return $s.Trim()
}

function HealthCode {
    try { return (Invoke-WebRequest -Uri $health -UseBasicParsing -TimeoutSec 8).StatusCode }
    catch { return 0 }
}

function WaitForDeployAndHealth([int]$timeoutSec = 420) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    Start-Sleep -Seconds 30
    while ((Get-Date) -lt $deadline) {
        if ((HealthCode) -eq 200) { return $true }
        Start-Sleep -Seconds 15
    }
    return $false
}

Log ("watchdog.start dryRun={0} branch={1} maxFixesPerHour={2}" -f $DryRun, $Branch, $MaxFixesPerHour)

$seen     = @{}
$fixTimes = New-Object System.Collections.ArrayList
$lastLogTime = Get-Date

while ($true) {
    $cutoff = (Get-Date).AddHours(-1)
    $fixTimes = [System.Collections.ArrayList]@($fixTimes | Where-Object { $_ -gt $cutoff })
    if ($fixTimes.Count -ge $MaxFixesPerHour) {
        Log ("breaker.tripped {0} fixes in last hour; halting for human" -f $fixTimes.Count)
        $b = ("### [{0}] HALT - circuit breaker`r`n- **Why:** {1} autonomous fixes in the last hour (limit {2}).`r`n- **Loop state:** STOPPED. A human should review recent entries before re-enabling." -f (Stamp), $fixTimes.Count, $MaxFixesPerHour)
        Ledger $b
        break
    }

    $since = $lastLogTime.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $lastLogTime = Get-Date
    $raw = & docker logs --since $since $container 2>&1
    $errLines = $raw | Where-Object {
        $_ -match 'ERROR|Exception|Caused by|FAILED|OutOfMemory|Unexpected error' -and
        $_ -notmatch 'PageImpl|PagedModel|SpringDataJackson|WarningLoggingModifier'
    }

    foreach ($line in $errLines) {
        $sig = Signature $line
        if ([string]::IsNullOrWhiteSpace($sig)) { continue }
        if ($seen.ContainsKey($sig)) { continue }
        $seen[$sig] = Get-Date

        $shortSig = $sig.Substring(0, [Math]::Min(60, $sig.Length))
        Log ("detect new signature: {0}" -f $shortSig)

        $prompt = @'
You are running NON-INTERACTIVELY as an autonomous bug-fixer for the economizai
Spring Boot project (Java 21). A live error was detected in the app logs:

__ERRLINE__

Your job: find the ROOT CAUSE in the codebase and apply a minimal, correct fix.
Rules:
- Follow the conventions in CLAUDE.md.
- Add or update a unit test that covers the bug (required).
- Do NOT commit or push - just edit files. The watchdog handles git + build.
- If you cannot confidently identify the root cause, make NO changes and reply
  exactly: NO_FIX_FOUND <one-line reason>.
- When done with a fix, reply with: FIXED <one-line summary of root cause + fix>.
'@
        $prompt = $prompt.Replace('__ERRLINE__', $line)

        $claudeOut = ($prompt | & claude -p --dangerously-skip-permissions --output-format text 2>&1 | Out-String).Trim()
        Log ("claude.reply {0}" -f $claudeOut.Substring(0, [Math]::Min(160, $claudeOut.Length)))

        if ($claudeOut -match 'NO_FIX_FOUND') {
            Ledger ("### [{0}] NO-FIX - {1}`r`n- **Error:** ``{2}```r`n- **Outcome:** Claude could not confidently diagnose; no change made.`r`n- **Detail:** {3}" -f (Stamp), $shortSig, $line, $claudeOut)
            continue
        }

        # GATE 1: build + tests
        Log "build.start mvnw test"
        $null = & cmd /c ('"{0}\mvnw.cmd" -q test 2>&1' -f $repo)
        $buildOk = ($LASTEXITCODE -eq 0)
        if (-not $buildOk) {
            Log "build.fail discarding changes"
            & git checkout -- . 2>$null; & git clean -fd 2>$null
            Ledger ("### [{0}] BUILD-FAIL - {1}`r`n- **Error:** ``{2}```r`n- **Claude:** {3}`r`n- **Outcome:** fix discarded - mvnw test failed, nothing pushed." -f (Stamp), $shortSig, $line, $claudeOut)
            continue
        }
        Log "build.pass"

        if ($DryRun) {
            Log "dryrun built OK; NOT pushing; reverting tree"
            & git stash -u 2>$null
            Ledger ("### [{0}] DRYRUN - {1}`r`n- **Error:** ``{2}```r`n- **Claude:** {3}`r`n- **Build:** PASS`r`n- **Outcome:** dry-run - fix built but NOT pushed (stashed)." -f (Stamp), $shortSig, $line, $claudeOut)
            continue
        }

        & git add -A
        $msg = ("fix(auto): " + ($claudeOut -replace '^FIXED\s*','' -replace '[\r\n]+',' '))
        if ($msg.Length -gt 100) { $msg = $msg.Substring(0, 100) }
        & git commit -q -m $msg
        $sha = (& git rev-parse --short HEAD).Trim()
        & git pull --rebase origin $Branch 2>$null
        & git push origin $Branch 2>&1 | Out-Null
        [void]$fixTimes.Add((Get-Date))
        Log ("push.done sha={0}" -f $sha)

        if (WaitForDeployAndHealth) {
            Log ("deploy.healthy sha={0}" -f $sha)
            Ledger ("### [{0}] FIX {1} - {2}`r`n- **Error:** ``{3}```r`n- **Root cause + fix:** {4}`r`n- **Build:** PASS (mvnw test)`r`n- **Deploy:** pushed {1} -> auto-deploy, health **UP**`r`n- **Outcome:** RESOLVED" -f (Stamp), $sha, $shortSig, $line, $claudeOut)
        } else {
            Log ("deploy.unhealthy auto-reverting sha={0}" -f $sha)
            & git revert --no-edit $sha 2>&1 | Out-Null
            $rev = (& git rev-parse --short HEAD).Trim()
            & git push origin $Branch 2>&1 | Out-Null
            $recovered = WaitForDeployAndHealth
            Ledger ("### [{0}] ROLLBACK {1} - reverted {2}`r`n- **Error being fixed:** ``{3}```r`n- **Attempted fix:** {4}`r`n- **Why reverted:** /actuator/health did NOT return UP after deploy.`r`n- **Revert commit:** {1}, health recovered: {5}`r`n- **Loop state:** kept running. Signature left for human review.`r`n- **Note for human:** the autonomous fix for this bug FAILED in production - needs eyes." -f (Stamp), $rev, $sha, $line, $claudeOut, $recovered)
            $seen.Remove($sig)
        }
    }

    Start-Sleep -Seconds $PollSeconds
}
