# Autonomous bug fixer (GitHub Actions)

The autonomous fixer that used to run on the local Windows box (`auto-fix-watchdog.ps1`)
now runs in GitHub Actions against the live Render dev server. Same contract:
reproduce-first, `mvnw test` gate, push to `development`, wait for the Render deploy +
health, auto-revert on failure. It also **learns** over time.

## Pieces
| File | Role |
|---|---|
| `.github/workflows/e2e-daily.yml` | Nightly (06:00 UTC) newman run of the Postman **E2E Flow** against the live dev API. On failure → fixer. |
| `.github/workflows/log-sweep.yml` | Every 2h: pulls recent Render logs, finds a NEW app error (deduped vs the ledger) → fixer. |
| `.github/workflows/autofix.yml` | Reusable fixer job (reproduce → test → push → deploy-wait → health → revert). |
| `.github/scripts/autofix.py` | The fixer + learning loop. |
| `.github/scripts/log_sweep.py` | Render Logs API scan + detection rules. |
| `.github/scripts/e2e_context.py`, `run-e2e.sh` | Newman run + failure-context builder. |
| `AUTONOMOUS_FIXES.md` | Audit ledger — one block per action (FIX / NEEDS-HUMAN / ROLLBACK / HALT). |
| `AUTOFIX_LESSONS.md` | The memory: durable lessons read into every fixer prompt. |

## How it learns
Before each attempt the fixer prompt is injected with (a) the most recent ~40 lessons
from `AUTOFIX_LESSONS.md` and (b) every prior ledger entry touching the *same* error
signature. After each outcome it distills a new one-line lesson (Claude may also emit an
explicit `LESSON <...>`) and appends it. A signature that was "fixed" but recurs is
flagged so the fixer looks for the real root cause instead of re-applying the same change.
No-fix outcomes are committed with `[skip render]` so recording never triggers a deploy.

## Safety rails
- Reproduce-first: no production change without a test that first FAILS on the bug, and
  the fixer independently checks that test is in the diff.
- `mvnw test` (full suite) must pass or the fix is discarded.
- After deploy, if `/actuator/health` isn't UP the commit is auto-reverted.
- Circuit breaker: halts after 3 fixes in one hour.

## Required GitHub secrets
| Secret | Set? | Notes |
|---|---|---|
| `RENDER_API_KEY` | ✅ set | Render logs + deploy-status polling. |
| `CLAUDE_CODE_OAUTH_TOKEN` | ⛔ **you must add** | `claude setup-token` on the machine logged into the fixer's Claude account, then paste the token. |
| `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD` | optional | Admin E2E steps skip without them. |
| `E2E_QR_PAYLOAD` | optional | A real NFC-e QR string; receipt-scan E2E steps skip without it. |

## Trigger manually
`gh workflow run e2e-daily.yml` or `gh workflow run log-sweep.yml`.
