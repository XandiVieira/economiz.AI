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

# NOT "Stop": native CLIs (docker, git) writing to stderr become terminating
# NativeCommandErrors under "Stop" and can kill the loop in the hidden Scheduled
# Task context (where docker emits stderr warnings the interactive session does
# not). Real failures are guarded with explicit try/catch and $LASTEXITCODE
# checks, so a noisy-but-successful native command must NOT abort the process.
$ErrorActionPreference = "Continue"
$repo     = "C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI"
$ledger   = Join-Path $repo "AUTONOMOUS_FIXES.md"
# Log lives OUTSIDE OneDrive: sync transiently locks files it watches, and a
# locked log would otherwise kill the loop on its first write. The ledger must
# stay in-repo (it's committed) and is guarded by Write-FileWithRetry instead.
$logDir   = Join-Path $env:LOCALAPPDATA "economizai"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
$logFile  = Join-Path $logDir "auto-fix-watchdog.log"
$health   = "http://localhost:8080/actuator/health"
$javaHome = "C:\Users\Xandi\.jdks\openjdk-21"
$container = "economizai-app"
$MARKER   = "AUTONOMOUS ENTRIES BELOW"

Set-Location $repo
$env:JAVA_HOME = $javaHome

# Write to a file, retrying through transient locks (e.g. OneDrive/AV holding
# the handle). A failed write must NEVER kill the watchdog loop.
function Write-FileWithRetry([scriptblock]$write) {
    for ($i = 0; $i -lt 6; $i++) {
        try { & $write; return $true }
        catch [System.IO.IOException] { Start-Sleep -Milliseconds 250 }
        catch { Start-Sleep -Milliseconds 250 }
    }
    return $false
}

function Log([string]$msg) {
    $line = ("{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg)
    Write-Host $line
    [void](Write-FileWithRetry { Add-Content -Path $logFile -Value $line -ErrorAction Stop })
}

# Insert a finished markdown block into the ledger, right after the marker line.
function Ledger([string]$block) {
    [void](Write-FileWithRetry {
        $content = Get-Content $ledger -Raw -ErrorAction Stop
        $content = $content -replace "(?s)_No autonomous fixes yet.*?detected bug\._", ""
        if ($content.IndexOf($MARKER) -ge 0) {
            $insertAt = $content.IndexOf("`n", $content.IndexOf($MARKER))
            $head = $content.Substring(0, $insertAt + 1)
            $tail = $content.Substring($insertAt + 1)
            Set-Content -Path $ledger -Value ($head + "`r`n" + $block.TrimEnd() + "`r`n" + $tail) -Encoding UTF8 -ErrorAction Stop
        } else {
            Add-Content -Path $ledger -Value ("`r`n`r`n" + $block) -ErrorAction Stop
        }
    })
}

function Stamp { return (Get-Date -Format "yyyy-MM-dd HH:mm:ss") }

# Pull an HTTP status code (4xx/5xx) out of the error text, if present.
function StatusCode([string]$text) {
    if ($text -match '\b(4\d{2}|5\d{2})\b') { return $Matches[1] }
    return ""
}

# Build the useful slice of a stacktrace for the ledger: the exception/message
# line, plus the FIRST stack frame in OUR code (com.relyon...), plus the first
# "Caused by:" if there is one. Avoids dumping a 60-line trace into the history.
# $allLines = the full docker-logs array; $startIdx = index of the error line.
function ErrorSnippet($allLines, [int]$startIdx) {
    $out = New-Object System.Collections.ArrayList
    [void]$out.Add(($allLines[$startIdx] -replace '\s+$',''))
    $ourFrame = $null; $causedBy = $null
    $limit = [Math]::Min($allLines.Count - 1, $startIdx + 40)
    for ($i = $startIdx + 1; $i -le $limit; $i++) {
        $l = "$($allLines[$i])"
        if (-not $ourFrame -and $l -match '^\s*at\s+com\.relyon\.') { $ourFrame = $l.Trim() }
        if (-not $causedBy -and $l -match '^\s*Caused by:') { $causedBy = $l.Trim() }
        if ($ourFrame -and $causedBy) { break }
        # stop once we've left this stacktrace (next timestamped log line)
        if ($i -gt $startIdx + 1 -and $l -match '^\d{4}-\d{2}-\d{2} ') { break }
    }
    if ($ourFrame) { [void]$out.Add($ourFrame) }
    if ($causedBy) { [void]$out.Add($causedBy) }
    return ($out -join "`n")
}

# Bring the local branch up to date with origin BEFORE diagnosing/fixing, so the
# Claude fix and the build run against the latest code. Returns $true if the tree
# is clean and current; $false if a rebase conflict (or other error) left it
# dirty - in which case the caller must abort this cycle, not fix on a bad base.
function SyncBranch {
    & git fetch origin $Branch 2>$null
    & git rebase "origin/$Branch" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        # Conflict or other rebase failure - abort cleanly, leave a pristine tree.
        & git rebase --abort 2>$null | Out-Null
        & git reset --hard "origin/$Branch" 2>$null | Out-Null
        return $false
    }
    return $true
}

# Push the just-made commit, surviving the common race where another machine (or
# the app's own CI) pushed between our commit and our push. Re-syncs and retries
# up to $maxTries with a short backoff. Returns $true on success; on exhaustion
# returns $false WITHOUT touching the tree (caller decides how to clean up).
function PushWithRetry([int]$maxTries = 3) {
    for ($t = 1; $t -le $maxTries; $t++) {
        & git pull --rebase origin $Branch 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            & git rebase --abort 2>$null | Out-Null
            Log ("push.retry rebase-conflict try=$t/$maxTries")
            Start-Sleep -Seconds ($t * 3)
            continue
        }
        & git push origin $Branch 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return $true }
        Log ("push.retry push-rejected try=$t/$maxTries")
        Start-Sleep -Seconds ($t * 3)
    }
    return $false
}

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
$failCount = @{}   # signature -> how many times an autonomous fix has FAILED for it
$transientHits = @{}   # signature -> ArrayList of timestamps (for transient recurrence gate)
$fixTimes = New-Object System.Collections.ArrayList
$lastLogTime = Get-Date

# Errors from known external services (SEFAZ, geocoder, network) are often
# transient - they fail once then succeed on retry. We must NOT change code for
# those; we wait for them to RECUR before treating them as a real bug.
$TransientThreshold = 3                       # need this many hits...
$TransientWindow    = New-TimeSpan -Minutes 10 # ...within this window to act
function IsTransient([string]$line) {
    return ($line -match 'SEFAZ|Sefaz|Svrs|Nominatim|geocode|SocketTimeout|ConnectException|UnknownHost|Read timed out|Connection reset|503|502|504|HttpServerErrorException|ResourceAccessException|RestClientException')
}

while ($true) {
    $cutoff = (Get-Date).AddHours(-1)
    $fixTimes = [System.Collections.ArrayList]@($fixTimes | Where-Object { $_ -gt $cutoff })
    if ($fixTimes.Count -ge $MaxFixesPerHour) {
        Log ("breaker.tripped {0} fixes in last hour; halting for human" -f $fixTimes.Count)
        $b = ("### [{0}] HALT - circuit breaker`r`n- **Why:** {1} autonomous fixes in the last hour (limit {2}).`r`n- **Loop state:** STOPPED. A human should review recent entries before re-enabling." -f (Stamp), $fixTimes.Count, $MaxFixesPerHour)
        Ledger $b
        break
    }

  try {
    $since = $lastLogTime.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $lastLogTime = Get-Date
    $raw = @(& docker logs --since $since $container 2>&1)

    # Iterate by index so we can reach into the following lines for the
    # stacktrace snippet (status code + error message + our first frame).
    for ($li = 0; $li -lt $raw.Count; $li++) {
        $line = "$($raw[$li])"
        # Only treat ERROR/FATAL-level lines (or raw stacktrace continuations) as
        # bugs. Matching bare 'Exception' anywhere caught the logger CLASS NAME
        # 'GlobalExceptionHandler' on EVERY line it logged - including expected
        # WARN/400 validation messages. Gate on the log LEVEL token instead.
        $isError = ($line -match '\b(ERROR|FATAL)\b') -or
                   ($line -match '^\s*(at\s+\w|Caused by:|\.\.\.\s+\d+\s+more)') -or
                   ($line -match 'Exception(:| in thread)') -or
                   ($line -match 'OutOfMemory|Unexpected error')
        if (-not $isError) { continue }
        if ($line -match 'PageImpl|PagedModel|SpringDataJackson|WarningLoggingModifier') { continue }

        $sig = Signature $line
        if ([string]::IsNullOrWhiteSpace($sig)) { continue }
        if ($seen.ContainsKey($sig)) { continue }

        $snippet    = ErrorSnippet $raw $li
        $statusCode = StatusCode $snippet
        # Reusable diagnostic lines appended to every ledger entry for this error,
        # so the bug-fix history always carries the status code + the meaningful
        # slice of the stacktrace (message + our first frame), not just one line.
        $statusLine = if ($statusCode) { "- **Status code:** $statusCode`r`n" } else { "" }
        $diag = ("{0}- **Error snippet:**`r`n``````r`n{1}`r`n``````" -f $statusLine, $snippet)
        $seen[$sig] = Get-Date

        $shortSig = $sig.Substring(0, [Math]::Min(60, $sig.Length))
        Log ("detect new signature: {0}" -f $shortSig)

        # Transient-error gate: a known external/network error must RECUR
        # ($TransientThreshold times within $TransientWindow) before we attempt a
        # code fix. A one-off SEFAZ blip is logged and skipped - retry will likely
        # succeed. Recurrence means it's a real bug, not the service hiccuping.
        if (IsTransient $line) {
            if (-not $transientHits.ContainsKey($sig)) { $transientHits[$sig] = New-Object System.Collections.ArrayList }
            $cut = (Get-Date) - $TransientWindow
            $transientHits[$sig] = [System.Collections.ArrayList]@($transientHits[$sig] | Where-Object { $_ -gt $cut })
            [void]$transientHits[$sig].Add((Get-Date))
            $hits = $transientHits[$sig].Count
            if ($hits -lt $TransientThreshold) {
                Log ("transient.skip {0} hits={1}/{2} (likely a retry-able blip, not fixing)" -f $shortSig, $hits, $TransientThreshold)
                $seen.Remove($sig)   # let it be re-evaluated next time it appears
                continue
            }
            Log ("transient.recurring {0} hits={1} >= {2}; treating as real bug" -f $shortSig, $hits, $TransientThreshold)
        }

        # Update to latest origin BEFORE diagnosing, so the fix + build run on
        # current code (another machine may have pushed while we polled). If the
        # branch can't be cleanly synced, skip this cycle rather than fix on a
        # stale/dirty base - the error will resurface and we'll retry next round.
        if (-not (SyncBranch)) {
            Log ("sync.skip {0} could not fast-forward to origin/$Branch; deferring" -f $shortSig)
            $seen.Remove($sig)
            continue
        }

        $prompt = @'
You are running NON-INTERACTIVELY as an autonomous bug-fixer for the economizai
Spring Boot project (Java 21). A live error was detected in the app logs:

__ERRLINE__

Work in this EXACT order - REPRODUCE FIRST, then fix:

STEP 1 - REPRODUCE: Write a unit test that FAILS because of this bug, exposing
the exact faulty behavior from the log line above. Run ONLY that test and
confirm it fails for the right reason. This proves the bug is real and that you
understood it. Do NOT change any production code yet.
  - If you cannot write a test that reproduces the bug (not enough info, not a
    code bug, environmental/external), make NO changes and reply exactly:
    REPRO_FAIL <one-line reason>

STEP 2 - FIX: Only after the test fails as expected, apply a minimal, correct
fix so that same test now PASSES and nothing else breaks.

Rules:
- Follow the conventions in CLAUDE.md (test location, style, fixtures).
- Do NOT commit or push - just edit files. The watchdog handles git + build.
- Final reply MUST be one line, one of:
    REPRO_FAIL <reason>                                  (could not reproduce)
    FIXED <FullyQualifiedTestClass#testMethod> | <root cause + fix summary>
  The test reference is REQUIRED on a FIXED reply - the watchdog re-runs that
  exact test to independently verify your fix.
'@
        $prompt = $prompt.Replace('__ERRLINE__', $line)

        # Call the headless fixer in a child job so a HANG can't freeze the loop,
        # and wrap in try/catch so a crash logs + skips instead of killing us.
        # (A single un-timed `claude -p` previously took the whole watchdog down.)
        $ClaudeTimeoutSec = 600
        $claudeOut = ""
        try {
            $job = Start-Job -ScriptBlock {
                param($p)
                $p | & claude -p --dangerously-skip-permissions --output-format text 2>&1 | Out-String
            } -ArgumentList $prompt
            if (Wait-Job $job -Timeout $ClaudeTimeoutSec) {
                $claudeOut = (Receive-Job $job | Out-String).Trim()
            } else {
                Stop-Job $job -ErrorAction SilentlyContinue
                Log ("claude.timeout {0} after {1}s; skipping (loop stays up)" -f $shortSig, $ClaudeTimeoutSec)
                $failCount[$sig] = ([int]$failCount[$sig]) + 1
                Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · CLAUDE-TIMEOUT (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Outcome:** the fixer call exceeded ${ClaudeTimeoutSec}s and was killed; no change made.`r`n- **Note for human:** bug still live; autonomous diagnosis timed out - needs eyes.")
                Remove-Job $job -Force -ErrorAction SilentlyContinue
                continue
            }
            Remove-Job $job -Force -ErrorAction SilentlyContinue
        } catch {
            Log ("claude.error {0}: {1}; skipping (loop stays up)" -f $shortSig, $_.Exception.Message)
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            $errMsg = $_.Exception.Message
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · CLAUDE-ERROR (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Outcome:** the fixer call threw: $errMsg`r`n- **Note for human:** bug still live; autonomous diagnosis errored out - needs eyes.")
            continue
        }
        if ([string]::IsNullOrWhiteSpace($claudeOut)) {
            Log ("claude.empty {0}; skipping" -f $shortSig)
            continue
        }
        Log ("claude.reply {0}" -f $claudeOut.Substring(0, [Math]::Min(160, $claudeOut.Length)))

        if ($claudeOut -match 'REPRO_FAIL|NO_FIX_FOUND') {
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            & git checkout -- . 2>$null; & git clean -fd 2>$null   # drop any partial test
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · NO-REPRO (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Outcome:** could NOT reproduce the bug with a failing test; no code changed.`r`n- **Detail:** $claudeOut`r`n- **Note for human:** this bug is still live and could not be auto-reproduced - needs eyes.")
            continue
        }

        # GATE 0: reproduction must be real. Claude claims FIXED <test#method>.
        # We independently confirm the named test (a) actually exists in the tree
        # and (b) is part of the diff - i.e. Claude really wrote a covering test,
        # not just edited prod code and asserted "fixed".
        $testRef = ""
        if ($claudeOut -match 'FIXED\s+([\w.]+#[\w]+)') { $testRef = $Matches[1] }
        if ([string]::IsNullOrWhiteSpace($testRef)) {
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            & git checkout -- . 2>$null; & git clean -fd 2>$null
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · NO-TESTREF (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Claude:** $claudeOut`r`n- **Outcome:** reply lacked a verifiable test reference; changes discarded (reproduction unproven).`r`n- **Note for human:** bug still live - needs eyes.")
            continue
        }
        $testClass = ($testRef -split '#')[0]
        $testFileName = ($testClass -split '\.')[-1] + ".java"
        $changedTests = (& git status --porcelain 2>$null) -match [regex]::Escape($testFileName)
        if (-not $changedTests) {
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            & git checkout -- . 2>$null; & git clean -fd 2>$null
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · NO-TEST-DIFF (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Claude:** $claudeOut`r`n- **Outcome:** claimed test ``$testRef`` but no matching test file was added/changed; changes discarded.`r`n- **Note for human:** bug still live; reproduction not proven - needs eyes.")
            continue
        }
        Log ("repro.verified test={0}" -f $testRef)

        # GATE 1: build + tests (full suite - the covering test must pass AND
        # nothing else may break)
        Log "build.start mvnw test"
        $null = & cmd /c ('"{0}\mvnw.cmd" -q test 2>&1' -f $repo)
        $buildOk = ($LASTEXITCODE -eq 0)
        if (-not $buildOk) {
            Log "build.fail discarding changes"
            & git checkout -- . 2>$null; & git clean -fd 2>$null
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · BUILD-FAIL (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Attempted fix:** $claudeOut`r`n- **Outcome:** fix discarded - mvnw test failed, nothing pushed.`r`n- **Note for human:** bug still live; the autonomous fix did not compile/pass tests - needs eyes.")
            continue
        }
        Log "build.pass"

        if ($DryRun) {
            Log "dryrun built OK; NOT pushing; reverting tree"
            & git stash -u 2>$null
            Ledger ("### [$(Stamp)] DRYRUN - $shortSig`r`n$diag`r`n- **Claude:** $claudeOut`r`n- **Build:** PASS`r`n- **Outcome:** dry-run - fix built but NOT pushed (stashed).")
            continue
        }

        & git add -A
        # Build the commit subject from the part after the "|" (the human summary);
        # the test ref is kept for the ledger, not the subject.
        $summary = $claudeOut -replace '^FIXED\s+[\w.]+#[\w]+\s*\|?\s*','' -replace '^FIXED\s*','' -replace '[\r\n]+',' '
        $msg = ("fix(auto): " + $summary.Trim())
        if ($msg.Length -gt 100) { $msg = $msg.Substring(0, 100) }
        & git commit -q -m $msg
        $sha = (& git rev-parse --short HEAD).Trim()

        # Resilient push: re-sync + retry through races. If it still can't land,
        # discard the local commit (reset to origin) so nothing stays stuck and
        # the next cycle starts clean - then flag for a human and move on.
        if (-not (PushWithRetry)) {
            Log ("push.failed sha={0} discarding local commit after retries" -f $sha)
            & git reset --hard "origin/$Branch" 2>$null | Out-Null
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · PUSH-FAILED (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Attempted fix:** $claudeOut`r`n- **Outcome:** fix built + committed locally but could NOT be pushed after retries; local commit discarded to keep the tree clean.`r`n- **Note for human:** bug still live; the fix is reproducible - re-run or apply manually - needs eyes.")
            $seen.Remove($sig)
            continue
        }
        [void]$fixTimes.Add((Get-Date))
        Log ("push.done sha={0}" -f $sha)

        if (WaitForDeployAndHealth) {
            Log ("deploy.healthy sha={0}" -f $sha)
            Ledger ("### [$(Stamp)] FIX $sha - $shortSig`r`n$diag`r`n- **Reproduced by:** ``$testRef`` (failed before fix, passes after)`r`n- **Root cause + fix:** $claudeOut`r`n- **Build:** PASS (mvnw test, full suite)`r`n- **Deploy:** pushed $sha -> auto-deploy, health **UP**`r`n- **Outcome:** RESOLVED")
        } else {
            Log ("deploy.unhealthy auto-reverting sha={0}" -f $sha)
            & git revert --no-edit $sha 2>&1 | Out-Null
            $rev = (& git rev-parse --short HEAD).Trim()
            # The revert MUST land to restore last-good - retry it too.
            if (-not (PushWithRetry)) { Log ("revert.push_failed rev={0} - manual restore may be needed" -f $rev) }
            $recovered = WaitForDeployAndHealth
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · ROLLBACK $rev (attempt $($failCount[$sig])x) - reverted $sha`r`n$diag`r`n- **Attempted fix:** $claudeOut`r`n- **Why reverted:** /actuator/health did NOT return UP after deploy.`r`n- **Revert commit:** $rev, health recovered: $recovered`r`n- **Loop state:** kept running. Signature left for human review.`r`n- **Note for human:** the autonomous fix for this bug FAILED in production - needs eyes.")
            $seen.Remove($sig)
        }
    }
  } catch {
    # Any unexpected error in one poll cycle (docker hiccup, git, etc.) must NOT
    # kill the watchdog. Log it and keep going.
    Log ("loop.error {0}; continuing" -f $_.Exception.Message)
  }

    Start-Sleep -Seconds $PollSeconds
}
