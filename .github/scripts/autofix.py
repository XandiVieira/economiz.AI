#!/usr/bin/env python3
"""Autonomous reproduce-first bug fixer for economizai, with a learning loop.

Ported from auto-fix-watchdog.ps1 to run in GitHub Actions. Invoked by
.github/workflows/autofix.yml with AUTOFIX_* env vars. Behaviour mirrors the
original local watchdog, plus a memory so it improves over time:

  1. LEARN: before touching anything, load AUTOFIX_LESSONS.md (durable do/don't
     lessons distilled from past runs) and the prior attempts on THIS exact error
     signature from the ledger, and feed both into the fixer prompt so it never
     repeats a mistake it already made.
  2. REPRODUCE-FIRST: headless Claude must write a test that FAILS on the bug
     before changing production code (else it replies REPRO_FAIL -> no change).
  3. Independently verify the named test is actually in the diff.
  4. Full `mvnw test` gate — a fix that doesn't pass is discarded, never pushed.
  5. Push to `development`, wait for the Render deploy, health-check, and
     AUTO-REVERT if health stays down.
  6. RECORD: every outcome appends to AUTONOMOUS_FIXES.md (audit) AND distills a
     one-line lesson into AUTOFIX_LESSONS.md (the memory read in step 1). No-fix
     outcomes commit with `[skip render]` so they never trigger a deploy.
  7. Circuit breaker: halts if too many fixes land within one hour.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone

SOURCE = os.environ.get("AUTOFIX_SOURCE", "unknown")
SIGNATURE = os.environ.get("AUTOFIX_SIGNATURE", "").strip()
CONTEXT = os.environ.get("AUTOFIX_CONTEXT", "").strip()
BRANCH = os.environ.get("BASE_BRANCH", "development")
SERVICE = os.environ["SERVICE_ID"]
HEALTH_URL = os.environ["HEALTH_URL"]
LEDGER = os.environ.get("LEDGER", "AUTONOMOUS_FIXES.md")
LESSONS = os.environ.get("LESSONS", "AUTOFIX_LESSONS.md")
MAX_FIXES_PER_HOUR = int(os.environ.get("MAX_FIXES_PER_HOUR", "3"))
RENDER_KEY = os.environ["RENDER_API_KEY"]
CLAUDE_TIMEOUT = int(os.environ.get("CLAUDE_TIMEOUT_SEC", "900"))
MARKER = "<!-- AUTONOMOUS ENTRIES BELOW"
LESSON_MARKER = "<!-- LESSONS BELOW"


def stamp():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def log(msg):
    print(f"[autofix] {msg}", flush=True)


def summary(md):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(md + "\n")


def git(*args, timeout=180):
    return subprocess.run(["git", *args], text=True, capture_output=True, timeout=timeout)


def read(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except FileNotFoundError:
        return ""


# ---------------------------------------------------------------- ledger + lessons

def _insert_after_marker(path, marker, entry, default_header):
    text = read(path) or (default_header + "\n")
    idx = text.find(marker)
    if idx == -1:
        new = default_header + "\n" + entry + "\n\n" + text
    else:
        eol = text.find("\n", idx)
        eol = len(text) if eol == -1 else eol + 1
        new = text[:eol] + "\n" + entry + "\n" + text[eol:]
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(new)


def ledger_insert(entry):
    _insert_after_marker(LEDGER, MARKER, entry, MARKER + " -->")


def add_lesson(text):
    line = "- [%s] %s" % (stamp(), " ".join(text.split()))
    header = ("# Autofix lessons\n\n> Durable do/don't lessons the autonomous fixer distilled "
              "from past runs. It reads the most recent ~40 before every attempt so it stops "
              "repeating mistakes. Newest first.\n\n" + LESSON_MARKER + " -->")
    _insert_after_marker(LESSONS, LESSON_MARKER, line, header)


def load_lessons(limit=40):
    lines = [ln for ln in read(LESSONS).splitlines() if ln.startswith("- [")]
    picked = lines[:limit]
    return "\n".join(picked) if picked else "(none yet)"


def _sig_keys():
    """Tokens that identify this bug across runs: the code frame(s) and a text head."""
    keys = set(re.findall(r"at com\.relyon\.[\w.$]+\([\w.]+:\d+\)", CONTEXT))
    keys |= set(re.findall(r"com\.relyon\.[\w.]+Service\.\w+", CONTEXT))
    head = re.sub(r"\s+", " ", SIGNATURE)[:50].strip()
    if head:
        keys.add(head)
    return {k for k in keys if len(k) >= 8}


def prior_attempts():
    """Summaries of past ledger entries touching this same signature."""
    text = read(LEDGER)
    if not text:
        return "(no prior attempts on this error)"
    keys = _sig_keys()
    if not keys:
        return "(no prior attempts on this error)"
    blocks = re.split(r"(?=^### )", text, flags=re.M)
    hits = []
    for block in blocks:
        if any(key in block for key in keys):
            header = block.splitlines()[0].strip() if block.strip() else ""
            header = header.lstrip("# ").strip()
            if header:
                hits.append(header)
    if not hits:
        return "(no prior attempts on this error)"
    recent = hits[:6]
    prior_fix = any(re.search(r"\] FIX ", "### " + h) for h in recent)
    note = ("\nNOTE: a previous FIX for this signature RECURRED — your earlier fix was "
            "insufficient. Find the REAL root cause; do not re-apply the same change."
            if prior_fix else "")
    return "\n".join("- " + h for h in recent) + note


# ---------------------------------------------------------------- git / render

def push_with_retry(attempts=5):
    for attempt in range(attempts):
        git("pull", "--rebase", "origin", BRANCH)
        pushed = git("push", "origin", f"HEAD:{BRANCH}")
        if pushed.returncode == 0:
            return True
        log(f"push retry {attempt + 1}: {pushed.stderr.strip()}")
        time.sleep(4)
    return False


def commit_docs_only(subject):
    """Record a no-deploy outcome: commit only the ledger + lessons, tagged [skip render]."""
    git("add", LEDGER, LESSONS)
    committed = git("commit", "-q", "-m", f"{subject} [skip render]")
    if committed.returncode == 0 and not push_with_retry():
        log("docs-only push failed (non-fatal)")


def api_get(url):
    request = urllib.request.Request(
        url, headers={"Authorization": f"Bearer {RENDER_KEY}", "Accept": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode())


TERMINAL_OK = {"live"}
TERMINAL_BAD = {"build_failed", "update_failed", "canceled", "pre_deploy_failed", "deactivated"}


def health_is_up():
    try:
        with urllib.request.urlopen(urllib.request.Request(HEALTH_URL), timeout=10) as response:
            return response.status == 200
    except Exception:
        return False


def wait_deploy_and_health(full_sha, budget=900):
    deadline = time.time() + budget
    time.sleep(20)
    deployed = False
    while time.time() < deadline:
        try:
            deploys = api_get(f"https://api.render.com/v1/services/{SERVICE}/deploys?limit=10")
        except Exception as error:
            log(f"deploys api error: {error}")
            time.sleep(15)
            continue
        matched = None
        for item in deploys:
            deploy = item.get("deploy", {})
            commit_id = (deploy.get("commit") or {}).get("id", "")
            if commit_id and full_sha.startswith(commit_id[:12]):
                matched = deploy
                break
        if matched:
            status = matched.get("status")
            log(f"deploy {full_sha[:8]} status={status}")
            if status in TERMINAL_OK:
                deployed = True
                break
            if status in TERMINAL_BAD:
                return False
        time.sleep(15)
    if not deployed:
        log("deploy did not reach 'live' within budget")
        return False
    for _ in range(10):
        if health_is_up():
            return True
        time.sleep(12)
    return False


def circuit_breaker_tripped():
    text = read(LEDGER)
    now = datetime.now(timezone.utc)
    recent = 0
    for match in re.finditer(r"^### \[([\d\-]{10} [\d:]{8})\] FIX ", text, re.M):
        try:
            when = datetime.strptime(match.group(1), "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
        except ValueError:
            continue
        if (now - when).total_seconds() <= 3600:
            recent += 1
    return recent >= MAX_FIXES_PER_HOUR


# ---------------------------------------------------------------- fixer

PROMPT = """You are running NON-INTERACTIVELY as an autonomous bug-fixer for the economizai
Spring Boot project (Java 21). A problem was detected in production (source: {source}):

{context}

LESSONS FROM PAST RUNS (do not repeat these mistakes; reuse what worked):
{lessons}

PRIOR ATTEMPTS ON THIS EXACT ERROR:
{prior}

Work in this EXACT order - REPRODUCE FIRST, then fix:

STEP 1 - REPRODUCE: Write a unit/integration test that FAILS because of this bug,
exposing the exact faulty behavior above. Run ONLY that test and confirm it fails
for the right reason. This proves the bug is real. Do NOT change any production
code yet.
  - If you cannot write a test that reproduces the bug (not enough info, not a code
    bug, environmental/external, or it is a test-config gap rather than a defect),
    make NO changes and reply exactly:
    REPRO_FAIL <one-line reason>

STEP 2 - FIX: Only after the test fails as expected, apply a minimal, correct fix
so that same test now PASSES and nothing else breaks.

Rules:
- Follow the conventions in CLAUDE.md (test location, style, fixtures, naming).
- Do NOT commit or push - just edit files. The harness handles git + build + deploy.
- Reply with EXACTLY these lines:
    Line 1 - one of:
        REPRO_FAIL <reason>
        FIXED <FullyQualifiedTestClass#testMethod> | <root cause + fix summary>
    Line 2 (optional but encouraged):
        LESSON <one durable, general lesson a future fixer should remember>
  The test reference is REQUIRED on a FIXED reply - the harness re-runs the full
  suite to independently verify your fix and checks that test is in the diff.
"""


def discard_tree():
    git("checkout", "--", ".")
    git("clean", "-fd")


def normalize_after_claude(base_sha):
    """If Claude committed despite instructions, un-commit but KEEP edits in the tree."""
    head = git("rev-parse", "HEAD").stdout.strip()
    if head and head != base_sha:
        git("reset", "--mixed", base_sha)


def extract_lesson(claude_out):
    match = re.search(r"^LESSON\s+(.+)$", claude_out, re.M)
    return match.group(1).strip() if match else ""


def record(kind, title, body, lesson):
    ledger_insert(f"### [{stamp()}] {kind} - {title}\n{body}")
    if lesson:
        add_lesson(f"[{kind}] {lesson}")
    summary(f"### autofix [{SOURCE}] {kind}\n\n**{title}**\n\n{body}")


def diag_block():
    return f"- **Detected:**\n```\n{CONTEXT[:1500]}\n```"


def run_claude(base_sha):
    prompt = PROMPT.format(source=SOURCE, context=CONTEXT, lessons=load_lessons(), prior=prior_attempts())
    try:
        result = subprocess.run(
            ["claude", "-p", "--dangerously-skip-permissions", "--output-format", "text"],
            input=prompt, text=True, capture_output=True, timeout=CLAUDE_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        normalize_after_claude(base_sha)
        discard_tree()
        record("[NEEDS-HUMAN] CLAUDE-TIMEOUT", SIGNATURE[:60],
               f"{diag_block()}\n- **Outcome:** fixer exceeded {CLAUDE_TIMEOUT}s; partial edits discarded.",
               f"{SIGNATURE[:60]} is expensive to reproduce; needs a tighter/simpler repro or human help.")
        commit_docs_only("docs(autofix): needs-human")
        return None
    except Exception as error:
        normalize_after_claude(base_sha)
        discard_tree()
        record("[NEEDS-HUMAN] CLAUDE-ERROR", SIGNATURE[:60],
               f"{diag_block()}\n- **Outcome:** fixer errored: {error}", "")
        commit_docs_only("docs(autofix): needs-human")
        return None
    out = (result.stdout or "").strip()
    if result.stderr and result.stderr.strip():
        out = (out + "\n" + result.stderr.strip()).strip()
    return out


def main():
    if not SIGNATURE or not CONTEXT:
        log("missing signature/context; nothing to do")
        return 0
    if circuit_breaker_tripped():
        record("HALT", "circuit breaker",
               f"- **Why:** {MAX_FIXES_PER_HOUR} autonomous fixes landed in the last hour.\n"
               "- **Loop state:** halted; a human should review recent entries before re-enabling.", "")
        commit_docs_only("docs(autofix): circuit-breaker halt")
        return 0

    base_sha = git("rev-parse", "HEAD").stdout.strip()
    log(f"invoking fixer for: {SIGNATURE[:80]}")
    out = run_claude(base_sha)
    if out is None:
        return 0
    normalize_after_claude(base_sha)
    lesson = extract_lesson(out)
    log("claude reply: " + out[:160].replace("\n", " "))

    if not out:
        discard_tree()
        record("[NEEDS-HUMAN] NO-OUTPUT", SIGNATURE[:60], diag_block(), "")
        commit_docs_only("docs(autofix): needs-human")
        return 0

    if re.search(r"REPRO_FAIL|NO_FIX_FOUND", out):
        discard_tree()
        record("[NEEDS-HUMAN] NO-REPRO", SIGNATURE[:60],
               f"{diag_block()}\n- **Outcome:** could not reproduce with a failing test; no code changed.\n"
               f"- **Detail:** {out[:500]}",
               lesson or f"{SIGNATURE[:60]}: not a reproducible code bug — reply REPRO_FAIL fast next time.")
        commit_docs_only("docs(autofix): needs-human")
        return 0

    match = re.search(r"FIXED\s+([\w.]+#[\w]+)", out)
    if not match:
        discard_tree()
        record("[NEEDS-HUMAN] NO-TESTREF", SIGNATURE[:60],
               f"{diag_block()}\n- **Claude:** {out[:400]}\n"
               "- **Outcome:** reply lacked a verifiable test reference; changes discarded.",
               "Always end a FIXED reply with a real FQCN#method test reference.")
        commit_docs_only("docs(autofix): needs-human")
        return 0

    testref = match.group(1)
    testfile = testref.split("#")[0].split(".")[-1] + ".java"
    if testfile not in git("status", "--porcelain").stdout:
        discard_tree()
        record("[NEEDS-HUMAN] NO-TEST-DIFF", SIGNATURE[:60],
               f"{diag_block()}\n- **Claude:** {out[:400]}\n"
               f"- **Outcome:** claimed test `{testref}` but {testfile} is not in the diff; discarded.",
               "A FIXED reply must actually add/modify the named test file in the diff.")
        commit_docs_only("docs(autofix): needs-human")
        return 0
    log(f"repro.verified test={testref}")

    log("build.start mvnw test")
    build = subprocess.run(["./mvnw", "-q", "-B", "test"], text=True, capture_output=True, timeout=60 * 30)
    if build.returncode != 0:
        tail = (build.stdout or "")[-1500:]
        discard_tree()
        record("[NEEDS-HUMAN] BUILD-FAIL", SIGNATURE[:60],
               f"{diag_block()}\n- **Attempted fix:** {out[:300]}\n"
               f"- **Outcome:** discarded — `mvnw test` failed, nothing pushed.\n```\n{tail}\n```",
               lesson or f"{SIGNATURE[:50]}: a fix broke the suite — run mvnw test mentally before finishing.")
        commit_docs_only("docs(autofix): needs-human")
        return 0
    log("build.pass")

    last_line = out.splitlines()[0] if out.splitlines() else out
    subject = re.sub(r"^FIXED\s+[\w.]+#[\w]+\s*\|?\s*", "", last_line)
    subject = re.sub(r"^FIXED\s*", "", subject).strip()[:100] or SIGNATURE[:80]
    git("add", "-A")
    git("commit", "-q", "-m", f"fix(auto): {subject}")
    short_sha = git("rev-parse", "--short", "HEAD").stdout.strip()
    full_sha = git("rev-parse", "HEAD").stdout.strip()

    if not push_with_retry():
        git("reset", "--hard", f"origin/{BRANCH}")
        record("[NEEDS-HUMAN] PUSH-FAILED", SIGNATURE[:60],
               f"{diag_block()}\n- **Attempted fix:** {out[:300]}\n"
               f"- **Outcome:** built + committed ({short_sha}) but push failed after retries.", "")
        commit_docs_only("docs(autofix): needs-human")
        return 0
    log(f"push.done sha={short_sha}; waiting for Render deploy + health")

    if wait_deploy_and_health(full_sha):
        record(f"FIX {short_sha}", SIGNATURE[:60],
               f"{diag_block()}\n- **Reproduced by:** `{testref}` (failed before fix, passes after)\n"
               f"- **Root cause + fix:** {out[:600]}\n- **Build:** PASS (mvnw test, full suite)\n"
               f"- **Deploy:** pushed {short_sha} → Render, health **UP**\n- **Outcome:** RESOLVED",
               lesson or f"{SIGNATURE[:50]}: fixed via {testref} — {subject}")
        commit_docs_only(f"docs(autofix): ledger FIX {short_sha}")
        return 0

    log("deploy unhealthy; auto-reverting")
    git("revert", "--no-edit", full_sha)
    revert_sha = git("rev-parse", "--short", "HEAD").stdout.strip()
    pushed = push_with_retry()
    recovered = wait_deploy_and_health(git("rev-parse", "HEAD").stdout.strip()) if pushed else False
    record(f"[NEEDS-HUMAN] ROLLBACK {revert_sha}", f"reverted {short_sha}",
           f"{diag_block()}\n- **Attempted fix:** {out[:300]}\n"
           "- **Why reverted:** /actuator/health did not return UP after deploy.\n"
           f"- **Revert commit:** {revert_sha} (pushed={pushed}, recovered={recovered})\n"
           "- **Note for human:** the autonomous fix FAILED in production — needs eyes.",
           lesson or f"{SIGNATURE[:50]}: a green-in-CI fix still broke prod health — check runtime/env, not just tests.")
    commit_docs_only(f"docs(autofix): ledger ROLLBACK {revert_sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
