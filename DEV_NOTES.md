# economizai — dev shortcuts to revisit before prod

Things we shipped that work for development / early users but need an
upgrade before we have real volume / real revenue / real privacy stakes.
Each entry: **what's there now**, **why it's OK for dev**, **what to
change before prod**, **rough effort**.

When in doubt: search the codebase for `// TODO(prod)` markers — they
mirror entries here.

---

## Storage / infrastructure

### Profile picture storage = local disk
- **Now**: `LocalDiskProfilePictureStorage` writes to `/tmp/economizai/profile-pics/`. Bytes served via `GET /users/me/profile-picture`.
- **Why OK for dev**: zero setup, no external account, works on Render free tier.
- **Why NOT OK for prod**: Render free tier disk is **ephemeral** — wiped on every redeploy. Users would lose their pics on every push. Also can't scale to multiple instances.
- **Fix before prod**: implement an `S3ProfilePictureStorage` (or Cloudinary, or Render's paid persistent disk), wire via the `ProfilePictureStorage` interface, switch via env var. ~2 hr.

### FCM push notifications = stub
- **Now**: `PushDispatcher` logs the would-be payload, doesn't actually send. The notifications row gets `delivered=false`.
- **Why OK for dev**: no Firebase project / service-account JSON needed.
- **Why NOT OK for prod**: users won't get push notifications. PROMO_PERSONAL alerts are dead weight.
- **Fix before prod**: add `firebase-admin` Maven dep, drop service-account JSON in env (or as Render secret file), replace the `// TODO(FCM)` block in `PushDispatcher`. ~1 hr.

### SMTP email = disabled (impacts notifications + auth flows)
- **Now**: two paths consume SMTP:
  1. `EmailDispatcher` (notification channel) — `@ConditionalOnProperty(NOTIFICATIONS_EMAIL_ENABLED=true)`, defaults off, falls back to PUSH/NONE when off.
  2. `AuthEmailSender` (password reset + email verification) — always loaded; if SMTP isn't configured, **logs the link with `[DEV-MODE]` prefix** instead of sending. The reset/verify endpoints still return 204, so the FE flow works in dev — the developer copies the token from server logs.
- **Why OK for dev**: no SMTP creds, FE end-to-end testing still works (manually grab the link).
- **Why NOT OK for prod**: real users won't see a `[DEV-MODE]` log line. They get NO password-reset / verification email at all.
- **Fix before prod**: set SMTP creds in env (Render → `SMTP_HOST/PORT/USERNAME/PASSWORD`) and flip `NOTIFICATIONS_EMAIL_ENABLED=true`. Recommend SES, Mailgun, or Postmark — Gmail SMTP rate-limits hard. ~30 min.

---

## Security / secrets

### JWT secret in code default = weak placeholder
- **Now**: `application.yaml` has `JWT_SECRET=economizai-dev-secret-key-...for-hs256` as a fallback default. Production overrides via env.
- **Why OK for dev**: every dev machine has the same predictable token signing for testing.
- **Why NOT OK for prod**: if the env var is ever forgotten, the placeholder kicks in and anyone can forge tokens.
- **Fix before prod**: drop the default entirely so the app fails to start without a real secret. Or have `JwtService` panic-on-startup if the secret matches the known dev value. ~10 min.

### CORS still includes localhost
- **Now**: production `CORS_ORIGINS` env still has `http://localhost:3000,http://localhost:5173` — needed while FE is in dev.
- **Why NOT OK for prod**: any localhost-served page can hit prod with a logged-in user's token if they get one. Edge-case but real.
- **Fix before prod launch**: drop localhost entries, leave only the deployed FE origin(s). ~1 min in Render env tab.

---

## Hosting / cost

### Render free tier sleeps after 15 min idle
- **Now**: GitHub Actions cron pings `/legal/terms` every 14 min (`.github/workflows/keep-alive.yml`) so the JVM stays warm. 750 free hours/month covers always-on; bandwidth use is negligible.
- **Why OK for dev**: zero cost, hides the cold-start UX from the FE dev.
- **Why to revisit for prod**: paid Render plans don't sleep at all. Once we have organic traffic, the cron is just noise. Also the cron consumes GitHub Actions minutes (not many — but still).
- **Fix when ready**: drop the workflow file when (a) we move off free tier, or (b) organic traffic is high enough to keep the service warm. ~30 sec.

### Render free Postgres has limits too
- **Now**: free Postgres on Render — small storage cap, gets deleted after 90 days unless upgraded.
- **Why NOT OK for prod**: you'd lose all user data after 90 days.
- **Fix before any real users**: upgrade to a paid Postgres plan, or migrate to Supabase / Neon free tier (which doesn't expire). Document the chosen path. ~1 hr.

---

## Data correctness

### IBGE municipality code missing
- **Now**: `PriceObservation` carries `city` (string from Nominatim) + `state` (UF) but not the IBGE 7-digit municipality code that the FE spec wanted (PRO-53/54).
- **Why OK for dev**: city + state is enough for "show me everything in Porto Alegre".
- **Fix before prod B2B sales**: load the IBGE municipality CSV (5,570 rows) into a lookup table, backfill the column on existing rows, derive on geocode. Important for regional aggregation and for matching against external datasets (IBGE's own published prices, IPCA, etc). ~3 hr.

### PriceObservation can double-count when same chave appears in two households
- **Now**: per-household chave uniqueness lets two households both record the same fiscal NF. Each contributes its own PriceObservation rows → that fiscal event becomes 2+ observations in the panel.
- **Why OK for dev**: tiny edge case at low volume.
- **Why to revisit before B2B sells**: slight upward bias on sample counts and median stability for the same physical NF.
- **Fix when it matters**: dedup PriceObservation by (productId, marketCnpj, observedAt±tolerance) at write time, OR mark cross-household duplicates and keep only one in aggregates. ~4 hr.

---

## Monitoring / ops

### No `/metrics` endpoint
- **Now**: Spring Boot Actuator is in, but only `/actuator/health` is exposed. No `/actuator/prometheus` for metrics scraping.
- **Fix before serious ops**: change `management.endpoints.web.exposure.include` to add `prometheus` (and maybe `info`), wire to a Grafana/Prometheus endpoint. ~20 min.

### Logs go to stdout only — no aggregation
- **Now**: Render captures stdout. Searchable in their dashboard but no retention beyond the free-tier window.
- **Fix before serious ops**: ship logs to BetterStack, Loki, or Papertrail. Render has add-ons for this. ~1 hr.

---

## Last-checked: 2026-05-02

When you take care of an item above, **delete it from this file** instead
of marking it done — keep the file lean so what remains is what's
actually outstanding.
