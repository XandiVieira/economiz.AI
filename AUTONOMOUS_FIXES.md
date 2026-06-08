# Autonomous Bug-Fix Ledger

> **What this is.** `auto-fix-watchdog.ps1` watches the live `economizai-app`
> logs and, when it detects an error, autonomously diagnoses it, writes a fix,
> builds + tests it, and (if green) commits, pushes, and lets the existing
> auto-deploy ship it to the dev server. **Every action it takes is appended
> below** - including rollbacks. This is the audit trail for unattended fixes.
>
> **Autonomy level:** FULL (no human gate) - enabled by the repo owner on
> 2026-06-07, explicitly overriding the "ask before edits" rule in CLAUDE.md,
> for the self-hosted **dev** server only.
>
> **Safety rails that still apply:**
> - **Reproduce-first gate:** the fixer must write a test that FAILS on the bug
>   before fixing it. The watchdog independently verifies the named test exists
>   and is in the diff; if it can't reproduce (`REPRO_FAIL`) or gives no
>   verifiable test, **no code is changed** â€” it's logged `NEEDS-HUMAN` and the
>   loop moves on. A bug is only auto-fixed once it's been proven real.
> - Known-transient external errors (SEFAZ/geocoder/network/5xx) must **recur**
>   3Ã— within 10 min before any fix is attempted â€” a one-off blip is ignored.
> - The fixer call has a 600s timeout and is fully sandboxed in error handling:
>   a hang/crash logs `NEEDS-HUMAN` and the loop keeps running â€” it never freezes.
> - A fix that fails `mvnw test` is **never pushed**.
> - After deploy, if `/actuator/health` != `UP`, the commit is **auto-reverted**
>   (restoring last-good) and the loop keeps running.
> - Circuit breaker: if the same error signature recurs after a fix, or more
>   than N fixes happen in an hour, the loop **halts and waits for a human**.
> - Every entry here is written by the loop itself, newest at the top.
>
> **Finding what needs your attention.** Every block that failed and still needs
> a human is tagged `âš ï¸ NEEDS-HUMAN` â€” grep for it to list all open items:
> `grep "NEEDS-HUMAN" AUTONOMOUS_FIXES.md`. Three failure kinds carry it:
> `NO-FIX` (couldn't diagnose), `BUILD-FAIL` (fix didn't pass `mvnw test`),
> `ROLLBACK` (fix deployed but health stayed DOWN, so it was reverted). Each
> shows `(attempt Nx)` â€” how many times an autonomous fix for that same error
> signature has failed â€” so a recurring problem is obvious at a glance.

---

## How to read an entry

Each autonomous action produces one block:

```
### [2026-06-07 14:32:10] FIX a1b2c3d - geocode IllegalArgumentException
- **Trigger:** error signature seen in logs (count Nx)
- **Error:** <the log line / exception>
- **Root cause:** <one-line diagnosis>
- **Fix:** <what changed, which files>
- **Build:** PASS (mvnw test, 2 new tests)
- **Deploy:** pushed <sha>, auto-deploy SUCCESS
- **Health after deploy:** UP
- **Outcome:** RESOLVED (signature not seen again in 30m)
```

A rollback looks like:

```
### [2026-06-07 15:10:44] ROLLBACK e4f5g6h - reverted bad geocode fix
- **Reverting:** a1b2c3d (the fix above)
- **Why:** /actuator/health returned DOWN after deploy (HTTP 503)
- **Revert commit:** e4f5g6h, auto-deploy SUCCESS, health back to UP
- **Loop state:** kept running; signature re-queued for a fresh attempt
- **Note for human:** <anything that needs eyes>
```

---

<!-- AUTONOMOUS ENTRIES BELOW - newest first. The watchdog inserts here. -->

### [2026-06-07 22:46:08] âš ï¸ NEEDS-HUMAN Â· NO-REPRO (attempt 1x) - WARN  [    c.r.e.s.a.o.GoogleTokenVerifier - oauth.google.ve
- **Error snippet:**
```r
2026-06-08 01:42:30.113 WARN  [req=898a10d9 user= rcpt= item=] c.r.e.s.a.o.GoogleTokenVerifier - oauth.google.verify_failed ParseException: Invalid serialized unsecured/JWS/JWE object: Missing part delimiters
```
- **Outcome:** could NOT reproduce the bug with a failing test; no code changed.
- **Detail:** No production code changed; the throwaway probe is removed. 

The WARN log is the verifier correctly rejecting a non-JWT token (no dot delimiters ÔÇö an opaque/wrong/truncated value sent by the client in place of the Google ID token). `SignedJWT.parse` throws `ParseException`, which is already caught and converted to `InvalidOAuthTokenException`. A reproduction test of this input **passes** on current code (verified empirically), so there is no faulty behavior to expose ÔÇö it's a client-supplied bad token, not a code bug.

REPRO_FAIL Log is correct rejection of a malformed (no-dot-delimiter) client token; verifier already catches the ParseException and throws InvalidOAuthTokenException ÔÇö repro test passes on current code, so there is no code bug to fix.
- **Note for human:** this bug is still live and could not be auto-reproduced - needs eyes.



