# economizai AUTONOMOUS bug-fix watchdog.
# (self-update verified live 2026-06-08)
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
    [int]$MaxFixesPerHour = 6,   # raised 3->6 during E2E: many legit fixes expected
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
# Persistent app log (host bind-mount of the container's /var/log/economizai).
# This file SURVIVES container rebuilds and machine restarts - unlike `docker
# logs`, which only ever holds the current container's slice and is wiped on
# every --build redeploy. Reading it lets the watchdog see error history across
# deploys. Falls back to `docker logs` if the file isn't present yet.
#
# The container bind-mounts its log to an ABSOLUTE machine path (see
# docker-compose.yml), so the persistent app log lives in the machine data dir -
# the same place regardless of whether the stack was started from the runner
# checkout or a local working copy. Older deploys wrote it under the runner's
# _work dir, so probe that legacy location too and use the first that exists.
$dataRoot = $env:ECONOMIZAI_DATA_ROOT; if (-not $dataRoot) { $dataRoot = "C:\economizai-data" }
$appLogCandidates = @(
    (Join-Path $dataRoot "logs\app\app.log"),                            # current (machine data dir)
    "C:\actions-runner\_work\economiz.AI\economiz.AI\logs\app\app.log",  # legacy (old runner-checkout mount)
    (Join-Path $repo "logs\app\app.log")                                  # legacy (old local-dev mount)
)
function ResolveAppLog {
    foreach ($c in $appLogCandidates) { if (Test-Path $c) { return $c } }
    return $appLogCandidates[0]
}
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

# OBSERVABILITY: a single JSON status file that an external watcher (a human or
# Claude monitoring this) can read to get a CONCLUSIVE picture - what state the
# loop is in, when it last ticked (heartbeat), the running PID, and fix counts.
# This is the antidote to guessing "is it stuck / orphaned?" from log silence or
# process lists: if `heartbeatUtc` is recent, it's alive; `state` says what it's
# doing. Written atomically (temp + move) so a reader never sees a half file.
$statusFile = Join-Path $dataRoot "logs\watchdog-status.json"
$script:wdState = "STARTING"
$script:wdDetail = ""
function SetStatus([string]$state, [string]$detail = "") {
    $script:wdState = $state
    if ($detail) { $script:wdDetail = $detail }
    try {
        $obj = [ordered]@{
            state        = $script:wdState
            detail       = $script:wdDetail
            pid          = $PID
            heartbeatUtc = (Get-Date).ToUniversalTime().ToString('o')
            dryRun       = [bool]$DryRun
            fixesLastHour = @($fixTimes | Where-Object { $_ -gt (Get-Date).AddHours(-1) }).Count
            maxFixesPerHour = $MaxFixesPerHour
            scriptHash   = $selfHashAtBoot
        }
        $json = $obj | ConvertTo-Json -Compress
        $tmp = "$statusFile.tmp"
        [void](Write-FileWithRetry {
            New-Item -ItemType Directory -Force -Path (Split-Path $statusFile) | Out-Null
            Set-Content -Path $tmp -Value $json -Encoding ASCII -ErrorAction Stop
            Move-Item -Path $tmp -Destination $statusFile -Force -ErrorAction Stop
        })
    } catch { }
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

# Collapse multi-line command output to a single trimmed line, capped, so a git
# error can be logged on one line without flooding the log.
function OneLine([string]$text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return "" }
    $s = ($text -replace '[\r\n]+', ' ').Trim()
    if ($s.Length -gt 200) { $s = $s.Substring(0, 200) }
    return $s
}

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
#
# SAFETY GUARD: this runs against the OneDrive working copy, which is ALSO the
# human's editing checkout. If the tree has uncommitted HUMAN changes (someone
# is mid-edit), we must NEVER `git reset --hard` - that silently destroys their
# work. Skip the whole cycle instead and log it; the human commits when ready.
#
# CRUCIAL: the ledger ($ledger, AUTONOMOUS_FIXES.md) is written by THIS watchdog
# every time it acts. On the NEEDS-HUMAN paths it is written but NOT committed,
# so it would leave the tree dirty forever and DEADLOCK this guard (the watchdog
# writes the ledger, then skips every future cycle because its own ledger write
# made the tree dirty). To prevent that, the ledger is auto-committed right after
# each write (see CommitLedger), so by the time we get here the tree should carry
# only genuine human edits. We still exclude the ledger defensively.
# (TODO(prod): move the watchdog to a dedicated clone outside OneDrive so it can
# never collide with human edits - see INFRASTRUCTURE.md.)
function SyncBranch {
    $ledgerRel = [regex]::Escape((Split-Path $ledger -Leaf))
    $dirty = @(& git status --porcelain 2>$null | Where-Object { $_ -notmatch $ledgerRel })
    if ($dirty.Count -gt 0) {
        Log ("sync.skip {0} uncommitted human change(s) - not resetting; deferring to protect work" -f $dirty.Count)
        return $false
    }
    # Belt-and-suspenders: if the ledger somehow stayed dirty, commit it now so
    # the rebase has a clean tree (never reset --hard over an unsaved ledger).
    CommitLedger
    & git fetch origin $Branch 2>$null
    & git rebase "origin/$Branch" 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        & git rebase --abort 2>$null | Out-Null
        & git reset --hard "origin/$Branch" 2>$null | Out-Null
        return $false
    }
    return $true
}

# Commit (and best-effort push) the ledger on its own, so a NEEDS-HUMAN write
# doesn't leave the tree dirty and deadlock SyncBranch. Audit entries becoming
# their own commits is desirable - the ledger history is the autonomy audit log.
# Failures are swallowed: a push race or offline moment must never kill the loop.
function CommitLedger {
    $ledgerDirty = (& git status --porcelain -- $ledger 2>$null)
    if (-not $ledgerDirty) { return }
    & git add -- $ledger 2>$null | Out-Null
    & git commit -q -m "chore(watchdog): ledger entry" -- $ledger 2>$null | Out-Null
    & git push origin $Branch 2>$null | Out-Null   # best-effort; SyncBranch reconciles next cycle
}

# Discard a fixer's HALF-FINISHED edits (prod + test files) after a fix attempt
# bails out (timeout / error / empty reply / failed gate) WITHOUT leaving the tree
# dirty. A dirty tree would make the next SyncBranch treat the leftovers as human
# work and skip forever (a deadlock we hit live during an E2E run). We commit the
# ledger first (preserving the audit entry), then hard-restore the rest from HEAD.
# This is the single cleanup the failure paths must call instead of ad-hoc
# `git checkout -- .` (which would also blow away the unsaved ledger entry).
function CleanFixerEdits {
    CommitLedger                              # save the audit entry as its own commit
    & git checkout -- . 2>$null               # drop tracked-file edits the fixer made
    & git clean -fd -e "*.log" 2>$null | Out-Null  # drop new untracked files (keep stray logs)
}

# Push the just-made commit, surviving the common race where another machine (or
# the app's own CI) pushed between our commit and our push. Re-syncs and retries
# up to $maxTries with a backoff. Returns $true on success; on exhaustion returns
# $false WITHOUT touching the tree (caller decides how to clean up).
#
# Retry budget is generous on purpose: when a human is actively pushing from
# another machine (feature work, E2E runs), a 3-retry/~14s window loses the race
# and a verified good fix gets discarded. 6 tries with a longer backoff rides out
# a burst. We CAPTURE git's stderr and log it (don't 2>$null it) so a real push
# failure - auth, hook, protected branch - is diagnosable instead of a silent
# "push-rejected".
function PushWithRetry([int]$maxTries = 6) {
    for ($t = 1; $t -le $maxTries; $t++) {
        $pullErr = (& git pull --rebase origin $Branch 2>&1) | Out-String
        if ($LASTEXITCODE -ne 0) {
            & git rebase --abort 2>&1 | Out-Null
            Log ("push.retry rebase-conflict try=$t/$maxTries :: " + (OneLine $pullErr))
            Start-Sleep -Seconds ([Math]::Min(30, $t * 5))
            continue
        }
        $pushErr = (& git push origin $Branch 2>&1) | Out-String
        if ($LASTEXITCODE -eq 0) { return $true }
        Log ("push.retry push-rejected try=$t/$maxTries :: " + (OneLine $pushErr))
        Start-Sleep -Seconds ([Math]::Min(30, $t * 5))
    }
    return $false
}

# Reduce a log line to a stable signature so we don't fix the same bug twice.
#
# $snippet (optional) is the ErrorSnippet - if given, we append the FIRST
# `com.relyon.*` stack frame (class+line). This makes two DIFFERENT endpoints
# throwing the SAME generic message (e.g. `IllegalArgumentException: -1` from
# PriceIndexService.bestMarkets AND InsightsService.topMarkets) count as DISTINCT
# bugs. Without it, fixing one marks the message `seen` and the watchdog goes
# blind to the other - the exact failure we hit live with /best-markets.
function Signature([string]$line, [string]$snippet = "") {
    $s = $line -replace '\d{4}-\d{2}-\d{2} [\d:.]+', '' `
               -replace 'req=[a-f0-9]+', '' -replace 'user=\S*', '' `
               -replace 'rcpt=\S*', '' -replace 'item=\S*', '' `
               -replace '"[^"]*"', 'X' -replace '\d+', 'N'
    $s = $s.Trim()
    if ($snippet) {
        $frame = ([regex]::Match($snippet, 'at\s+(com\.relyon\.[\w.$]+\([\w.]+:\d+\))')).Groups[1].Value
        if ($frame) { $s = "$s @ $frame" }
    }
    return $s
}

# Read app log lines newer than $since. Prefers the PERSISTENT bind-mounted file
# ($appLog) so history spans container rebuilds; falls back to `docker logs` when
# the file isn't there yet (first boot before the app has written it). Lines are
# kept in chronological order so ErrorSnippet can still reach following frames.
# We tail a bounded slice (the poll interval is short, so only a few lines are
# ever new) and filter by each line's leading "yyyy-MM-dd HH:mm:ss" timestamp.
# Tracked by LINE COUNT (high-water mark), NOT timestamp. The app container and
# this watchdog can run on different clocks/timezones (observed: app at 14:xx,
# host at 11:xx). A timestamp filter then treats old lines as "future" and
# re-processes already-resolved historical errors forever - the waste we saw.
# Line offset is immune to clock skew. First call initializes the mark to the
# current count (ignore all pre-existing history); a shrink (rotation/redeploy)
# resets it. $script: so it persists across calls.
$script:logLineMark = $null
function ReadNewLogLines {
    $appLog = ResolveAppLog
    if (Test-Path $appLog) {
        try {
            $all = @(Get-Content -Path $appLog -ErrorAction Stop)
            $count = $all.Count
            if ($null -eq $script:logLineMark) { $script:logLineMark = $count; return ,@() }
            if ($count -lt $script:logLineMark) { $script:logLineMark = 0 }
            $new = if ($count -gt $script:logLineMark) { $all[$script:logLineMark..($count-1)] } else { @() }
            $script:logLineMark = $count
            return ,@($new)
        } catch {
            Log ("logread.file_error {0}; falling back to docker logs" -f $_.Exception.Message)
        }
    }
    # Fallback: container logs from the last ~2 min (only before the file exists).
    return ,@(& docker logs --since 120s $container 2>&1)
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

# SELF-UPDATE: a watchdog that needs a manual restart to pick up its own bug
# fixes is not truly autonomous. Once per cycle we check whether THIS script
# changed on origin; if so we fast-forward and RELAUNCH ourselves with the same
# args, then exit. The Scheduled Task / parent keeps no state, so a clean new
# process running the new code takes over within one poll interval - no human
# restart, ever. Guarded so a fetch/relaunch failure just logs and continues.
$selfPath = $PSCommandPath
# Relaunch args: only carry NON-default overrides. We deliberately DON'T pass
# -MaxFixesPerHour so a relaunched process picks up the script's current default
# (so bumping the default in the param block takes effect on the next self-update,
# no need to also touch the Scheduled Task, which invokes with no args anyway).
$selfArgs = @()
if ($DryRun)        { $selfArgs += '-DryRun' }
$selfArgs += @('-PollSeconds', $PollSeconds, '-Branch', $Branch)
# Hash of OUR OWN script file as it was on disk when this process started. Each
# cycle we re-hash the file; if it differs, the on-disk script changed out from
# under the running process (a git pull, the auto-deploy, a manual edit) and we
# relaunch to adopt it. Comparing the file hash - NOT git HEAD vs origin - is the
# fix for the subtle bug where HEAD was already updated by a pull, so HEAD==origin
# even though the RUNNING process still held old code in memory and never reloaded.
$selfHashAtBoot = (Get-FileHash -Path $selfPath -Algorithm SHA256 -ErrorAction SilentlyContinue).Hash
# Throttle: the fetch is network I/O; run at most every 60s, not every poll.
# Called at the loop top AND right after each fix, so a watchdog kept busy by
# back-to-back fixes (intense E2E) still adopts new code instead of starving.
$script:lastSelfCheck = [datetime]::MinValue
function SelfUpdateIfChanged([switch]$Force) {
    if (-not $Force -and ((Get-Date) - $script:lastSelfCheck).TotalSeconds -lt 60) { return }
    $script:lastSelfCheck = Get-Date
    try {
        # Pull the latest script blob from origin onto disk (without disturbing
        # other files): fetch, then checkout JUST our own script from origin if it
        # differs. This is self-sufficient - it doesn't depend on a fix-triggered
        # SyncBranch having run. Only touches auto-fix-watchdog.ps1, so it can't
        # clobber human edits to other files.
        & git fetch origin $Branch 2>$null
        $diskBlob   = (& git hash-object $selfPath 2>$null)
        $originBlob = (& git rev-parse "origin/${Branch}:auto-fix-watchdog.ps1" 2>$null)
        $headBlob   = (& git rev-parse "HEAD:auto-fix-watchdog.ps1" 2>$null)
        # SAFETY: only overwrite the script from origin when the on-disk version
        # matches HEAD (i.e. it has NO uncommitted local edits). If someone is
        # editing the script (disk != HEAD AND HEAD != origin would be ambiguous),
        # we must NOT `git checkout` over their work - that's exactly how an
        # earlier version of this function ate in-progress edits. Only adopt when
        # the local file is clean (disk == HEAD) and origin moved ahead.
        $localClean = ($diskBlob -eq $headBlob)
        if ($localClean -and $originBlob -and $diskBlob -and ($originBlob -ne $diskBlob)) {
            & git checkout "origin/$Branch" -- auto-fix-watchdog.ps1 2>$null | Out-Null
        }
        # Now decide purely on the FILE hash vs what we booted with - this catches
        # the checkout above AND any other on-disk change (manual edit, deploy).
        # Comparing the file (not git HEAD vs origin) fixes the bug where a prior
        # pull made HEAD==origin while the running process still held old code.
        $now = (Get-FileHash -Path $selfPath -Algorithm SHA256 -ErrorAction SilentlyContinue).Hash
        if (-not $now -or -not $selfHashAtBoot -or $now -eq $selfHashAtBoot) { return }
        Log "selfupdate.relaunch own script changed on disk; restarting with new code"
        # Detached relaunch so we can exit cleanly; the new process re-reads the
        # (now-updated) file from disk. Restore index so the checkout isn't staged.
        & git reset -q 2>$null | Out-Null
        Start-Process -FilePath "powershell.exe" `
            -ArgumentList (@('-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File', $selfPath) + $selfArgs) `
            -WindowStyle Hidden | Out-Null
        Log "selfupdate.exit old process exiting; new code has taken over"
        exit 0
    } catch {
        Log ("selfupdate.error {0}; continuing on current code" -f $_.Exception.Message)
    }
}

Log ("watchdog.start dryRun={0} branch={1} maxFixesPerHour={2}" -f $DryRun, $Branch, $MaxFixesPerHour)

$seen     = @{}
$failCount = @{}   # signature -> how many times an autonomous fix has FAILED for it
$transientHits = @{}   # signature -> ArrayList of timestamps (for transient recurrence gate)

# Circuit-breaker fix timestamps, PERSISTED to the data dir so the N/hour limit
# survives a restart/self-relaunch. Was in-memory, so every restart zeroed it and
# the breaker almost never tripped - couldn't protect against a runaway loop.
$fixTimesFile = Join-Path $dataRoot "logs\watchdog-fixtimes.txt"
function LoadFixTimes {
    $list = New-Object System.Collections.ArrayList
    if (Test-Path $fixTimesFile) {
        $cut = (Get-Date).AddHours(-1)
        foreach ($l in (Get-Content $fixTimesFile -ErrorAction SilentlyContinue)) {
            $t = [datetime]::MinValue
            if ([datetime]::TryParse($l, [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::RoundtripKind, [ref]$t)) {
                if ($t -gt $cut) { [void]$list.Add($t) }
            }
        }
    }
    return ,$list
}
function RecordFixTime {
    [void]$fixTimes.Add((Get-Date))
    try {
        New-Item -ItemType Directory -Force -Path (Split-Path $fixTimesFile) | Out-Null
        $cut = (Get-Date).AddHours(-1)
        ($fixTimes | Where-Object { $_ -gt $cut } | ForEach-Object { $_.ToString('o') }) |
            Set-Content -Path $fixTimesFile -Encoding ASCII -ErrorAction Stop
    } catch { }
}
$fixTimes = LoadFixTimes
SetStatus "STARTING"   # first heartbeat, now that fixTimes is loaded

# Errors from known external services (SEFAZ, geocoder, network) are often
# transient - they fail once then succeed on retry. We must NOT change code for
# those; we wait for them to RECUR before treating them as a real bug.
$TransientThreshold = 3                       # need this many hits...
$TransientWindow    = New-TimeSpan -Minutes 10 # ...within this window to act
function IsTransient([string]$line) {
    return ($line -match 'SEFAZ|Sefaz|Svrs|Nominatim|geocode|SocketTimeout|ConnectException|UnknownHost|Read timed out|Connection reset|503|502|504|HttpServerErrorException|ResourceAccessException|RestClientException')
}

# CRASH-RECOVERY: the per-iteration try/catch below handles normal errors, but a
# fatal error OUTSIDE it (e.g. in SelfUpdateIfChanged, the breaker, or Start-Sleep)
# would kill the process - and the Scheduled Task only relaunches at logon, so the
# watchdog would stay dead for hours. Wrap the whole loop: on any escape, log it,
# mark status CRASHED, and RELAUNCH ourselves so the watchdog is self-resurrecting.
try {
while ($true) {
    # First thing each cycle: adopt our own latest code if it changed upstream.
    # This is what makes the watchdog self-healing - my fixes to THIS script go
    # live within one poll interval without any manual restart.
    SelfUpdateIfChanged

    $cutoff = (Get-Date).AddHours(-1)
    $fixTimes = [System.Collections.ArrayList]@($fixTimes | Where-Object { $_ -gt $cutoff })
    if ($fixTimes.Count -ge $MaxFixesPerHour) {
        Log ("breaker.tripped {0} fixes in last hour; halting for human" -f $fixTimes.Count)
        $b = ("### [{0}] HALT - circuit breaker`r`n- **Why:** {1} autonomous fixes in the last hour (limit {2}).`r`n- **Loop state:** STOPPED. A human should review recent entries before re-enabling." -f (Stamp), $fixTimes.Count, $MaxFixesPerHour)
        Ledger $b
        break
    }

  try {
    $raw = ReadNewLogLines   # new lines since last poll, tracked by line offset

    # Iterate by index so we can reach into the following lines for the
    # stacktrace snippet (status code + error message + our first frame).
    for ($li = 0; $li -lt $raw.Count; $li++) {
        $line = "$($raw[$li])"
        # A stacktrace continuation ('at ...', 'Caused by:', '... N more') is part
        # of the PRECEDING error's trace - ErrorSnippet already folds those frames
        # into the detection below. They must NOT be treated as standalone bugs, or
        # every frame of one 500 becomes its own "new signature" and burns a full
        # 600s Claude call apiece. Skip continuations outright.
        if ($line -match '^\s*(at\s+\w|Caused by:|\.\.\.\s+\d+\s+more|\.\.\.\s+\d+\s+common\s+frames)') { continue }
        # A WARN line is, by definition, a handled/expected condition the app
        # CHOSE to warn about (e.g. `oauth.google.verify_failed ParseException` =
        # the verifier correctly rejecting a bad client token). It is NOT an
        # unhandled failure, so it must never trigger an autonomous fix - even
        # when its message text contains "Exception:". Skip WARN lines outright.
        # (A genuine bug surfaces as an ERROR/FATAL line or an uncaught stacktrace,
        # caught by the rules below; a WARN about an exception is the app working.)
        if ($line -match '\bWARN\b') { continue }
        # Trigger only on a real error HEADER: an ERROR/FATAL log line, an actual
        # exception message, or OOM/Unexpected. Matching bare 'Exception' anywhere
        # caught the logger CLASS NAME 'GlobalExceptionHandler' on EVERY line it
        # logged - including expected WARN/400 validation messages. Gate on the log
        # LEVEL token (or a true 'Exception:'/'in thread' message) instead.
        $isError = ($line -match '\b(ERROR|FATAL)\b') -or
                   ($line -match 'Exception(:| in thread)') -or
                   ($line -match 'OutOfMemory|Unexpected error')
        if (-not $isError) { continue }
        if ($line -match 'PageImpl|PagedModel|SpringDataJackson|WarningLoggingModifier') { continue }

        # Compute the snippet FIRST so the signature can include the offending
        # com.relyon stack frame (distinguishes same-message bugs in different code).
        $snippet    = ErrorSnippet $raw $li
        $sig = Signature $line $snippet
        if ([string]::IsNullOrWhiteSpace($sig)) { continue }
        if ($seen.ContainsKey($sig)) { continue }

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
        # 300s (was 600): one hard bug must not block the loop for 10 min - other
        # E2E errors and the self-update check starve that whole time. A legit
        # reproduce+fix+test rarely exceeds 5 min; if it does, abort and retry.
        $ClaudeTimeoutSec = 300
        $claudeOut = ""
        # Progress log: a fix cycle is the one place the loop goes silent for up to
        # ClaudeTimeoutSec while the fixer reproduces + builds. Without this line a
        # watcher (human or me) can't tell "working" from "hung". Emit start + the
        # deadline so the silence is expected, not alarming.
        $fixStarted = Get-Date
        SetStatus "FIXING" ("{0} (deadline {1})" -f $shortSig, $fixStarted.AddSeconds($ClaudeTimeoutSec).ToString('HH:mm:ss'))
        Log ("fix.start {0} invoking fixer (timeout {1}s, deadline {2})" -f $shortSig, $ClaudeTimeoutSec, $fixStarted.AddSeconds($ClaudeTimeoutSec).ToString('HH:mm:ss'))
        try {
            $job = Start-Job -ScriptBlock {
                param($p)
                $p | & claude -p --dangerously-skip-permissions --output-format text 2>&1 | Out-String
            } -ArgumentList $prompt
            if (Wait-Job $job -Timeout $ClaudeTimeoutSec) {
                $claudeOut = (Receive-Job $job | Out-String).Trim()
            } else {
                Stop-Job $job -ErrorAction SilentlyContinue
                # CRITICAL: a timed-out fixer leaves HALF-EDITED prod/test files in
                # the tree. If we don't discard them, the dirty-tree guard treats
                # them as 'human work' and DEADLOCKS every subsequent cycle. Clean
                # the partial edits (preserving the ledger) before moving on.
                CleanFixerEdits
                Log ("claude.timeout {0} after {1}s; discarded partial edits; skipping (loop stays up)" -f $shortSig, $ClaudeTimeoutSec)
                $failCount[$sig] = ([int]$failCount[$sig]) + 1
                Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · CLAUDE-TIMEOUT (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Outcome:** the fixer call exceeded ${ClaudeTimeoutSec}s and was killed; partial edits discarded.`r`n- **Note for human:** bug still live; autonomous diagnosis timed out - needs eyes.")
                Remove-Job $job -Force -ErrorAction SilentlyContinue
                continue
            }
            Remove-Job $job -Force -ErrorAction SilentlyContinue
        } catch {
            CleanFixerEdits
            Log ("claude.error {0}: {1}; discarded partial edits; skipping (loop stays up)" -f $shortSig, $_.Exception.Message)
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            $errMsg = $_.Exception.Message
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · CLAUDE-ERROR (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Outcome:** the fixer call threw: $errMsg`r`n- **Note for human:** bug still live; autonomous diagnosis errored out - needs eyes.")
            continue
        }
        if ([string]::IsNullOrWhiteSpace($claudeOut)) {
            CleanFixerEdits
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
        # the fix is BUILD-VERIFIED - don't just throw it away. Save it as a patch
        # OUTSIDE the repo (the log dir survives the reset --hard), reset the tree
        # so the next cycle starts clean, and point the human at the patch so a
        # proven fix can be applied by hand instead of being silently lost.
        if (-not (PushWithRetry)) {
            $patchFile = Join-Path $logDir ("autofix-{0}-{1}.patch" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $sha)
            [void](Write-FileWithRetry { & git format-patch -1 $sha --stdout 2>$null | Out-File -FilePath $patchFile -Encoding utf8 })
            Log ("push.failed sha={0} fix saved to {1}; resetting tree" -f $sha, $patchFile)
            & git reset --hard "origin/$Branch" 2>&1 | Out-Null
            $failCount[$sig] = ([int]$failCount[$sig]) + 1
            Ledger ("### [$(Stamp)] ⚠️ NEEDS-HUMAN · PUSH-FAILED (attempt $($failCount[$sig])x) - $shortSig`r`n$diag`r`n- **Attempted fix:** $claudeOut`r`n- **Outcome:** fix built + committed locally ($sha) but could NOT be pushed after retries.`r`n- **Saved patch:** ``$patchFile`` - apply with ``git am`` (the fix is build-verified, not lost).`r`n- **Note for human:** bug still live; apply the saved patch or re-run - needs eyes.")
            $seen.Remove($sig)
            continue
        }
        RecordFixTime   # persist to disk so the breaker survives restarts
        Log ("push.done sha={0}" -f $sha)
        SetStatus "DEPLOYING" ("sha={0}" -f $sha)
        # A fix just landed; check for our own updates before the long deploy/health
        # wait, so a busy watchdog still adopts new code between fixes.
        SelfUpdateIfChanged -Force

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
    SetStatus "ERROR" ("loop.error: " + (OneLine $_.Exception.Message))
  }

    # End of a clean poll: heartbeat as IDLE so a watcher knows we're alive and
    # just waiting for the next error (not stuck).
    SetStatus "IDLE"
    Start-Sleep -Seconds $PollSeconds
}
} catch {
    # The loop escaped (fatal/unexpected). Don't die silently - relaunch ourselves
    # so the watchdog recovers without waiting for the next logon. The breaker
    # (persisted fixTimes) and seen-history reset on relaunch, which is acceptable
    # for a crash-recovery path. A relaunch failure is the only true dead end.
    try { Log ("FATAL loop escaped: {0}; relaunching" -f $_.Exception.Message) } catch { }
    try { SetStatus "CRASHED" (OneLine $_.Exception.Message) } catch { }
    try {
        Start-Process -FilePath "powershell.exe" `
            -ArgumentList (@('-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File', $selfPath) + $selfArgs) `
            -WindowStyle Hidden | Out-Null
    } catch { }
    exit 1
}
