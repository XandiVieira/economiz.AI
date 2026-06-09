# economizai — dev shortcuts to revisit before prod

Things we shipped that work for development / early users but need an
upgrade before we have real volume / real revenue / real privacy stakes.
Each entry: **what's there now**, **why it's OK for dev**, **what to
change before prod**, **rough effort**.

When in doubt: search the codebase for `// TODO(prod)` markers — they
mirror entries here.

---

## Billing: apps ready (RevenueCat), web still pending
- **Now**: two webhook paths feed the same entitlement engine. **RevenueCat** (`POST /webhooks/revenuecat`, Authorization header == `REVENUECAT_WEBHOOK_AUTH`) covers iOS/Android IAP; the generic `POST /webhooks/subscription` (`BILLING_WEBHOOK_SECRET`, `X-Webhook-Secret`) is the seam for a web provider. Both **fail closed** when their secret is blank (constant-time compare). A scheduled `SubscriptionExpiryService` downgrades lapsed PRO hourly.
- **Why OK for dev**: dev grants PRO via the admin set-tier endpoint; webhooks aren't needed locally.
- **Before prod**:
  - **Apps:** set `REVENUECAT_WEBHOOK_AUTH`, and the app must set RevenueCat `app_user_id` = our user UUID (or email). That's it — env-var ready.
  - **Web:** NOT built yet — needs a Mercado Pago/Stripe **create-checkout endpoint** + a provider-specific webhook adapter (signature verify + event→activate/cancel mapping). Pending the provider choice (PIX-recurring vs one-off).

## LGPD data-export is incomplete
- **Now**: `UserService.exportData` covers the core account/receipt data but OMITS notification rules, watched markets, subscription, push token, manual purchases, and household market aliases / custom categories / category overrides.
- **Why OK for dev**: no real right-of-access requests yet.
- **Before prod**: extend `exportData` to include those entities for a complete, defensible LGPD export. ~half a day.

## ~~`bestMarkets` k-anon count is an N+1~~ — RESOLVED (2026-06-09)
- **Fixed**: `bestMarkets` now batches the distinct-household k-anon counts into one `GROUP BY` query (`countDistinctHouseholdsForProductByMarket` → `Map<cnpj,count>`) instead of one query per market. `referencePrice` still uses the single-market count (one product+market, no N+1).

---

## Social login (Google/Apple): audience check skipped until client IDs are set
- **Now**: `POST /api/v1/auth/google` and `/auth/apple` verify the provider token's signature (RS256 via the provider JWKS), issuer, and expiry. The **audience (`aud`) check only runs when `GOOGLE_OAUTH_CLIENT_IDS` / `APPLE_OAUTH_CLIENT_IDS` are configured**; when empty (dev default) it's skipped with a WARN.
- **Why OK for dev**: signature+issuer+expiry still prove the token is a genuine, current Google/Apple token. Fine for local testing.
- **Before prod**: set both env vars (comma-separated client IDs the mobile app may present as `aud`) so a token minted for a *different* app can't be replayed against us. Google: the OAuth client IDs per platform (Web/iOS/Android) from Google Cloud Console. Apple: the app bundle ID (native) and/or Service ID (web). Team ID + Sign-in-with-Apple key are NOT needed for this identity-token flow (only for the auth-code/refresh flow). JWKS are fetched + cached 1h in-memory (`CachingJwksKeySource`).

## Notification channels: Alexa / SMS / WhatsApp are stubs; email off by default
- **Now**: the channel framework supports `PUSH` (Expo → FCM/APNs, working), `EMAIL` (SMTP via `EmailDispatcher`, gated by `NOTIFICATIONS_EMAIL_ENABLED`, off by default until SMTP creds are set), and `ALEXA`/`SMS`/`WHATSAPP` which are **structure-only** (`AlexaDispatcher`/`SmsDispatcher`/`WhatsAppDispatcher` extend `StubChannelDispatcher` — they log and record a "not implemented" failure on the notification audit row). They're registered so users can select them, but nothing is delivered.
- **Why OK for dev**: graceful degradation — an unselected/stubbed channel just produces a failed audit row, never an exception. Push is the primary channel and works end-to-end.
- **Before prod**: implement `deliver()` in each stub — Alexa Proactive Events (skill grant per user), SMS (Twilio/Zenvia + phone verification), WhatsApp Cloud API (Meta template messages + phone verification). Add the per-user contact fields (phone) + verification flow. Flip `EMAIL` on once Render has SMTP env vars. Config placeholders already in `application.yaml` under `economizai.notifications.{alexa,sms,whatsapp}`.

## Community-default notifications: backfill + owners-query batched — MOSTLY RESOLVED (2026-06-09)
- **Fixed**: a startup `NotificationDefaultsBackfill` (`ApplicationRunner`) now proactively materializes missing default rule rows for **all existing users** (idempotent bulk `INSERT...SELECT`, one query per default type), so community notifications no longer wait for the user to open settings. (Lazy seeding on `GET /notification-rules` + absent-as-enabled still apply as a fallback.) Also `NotificationRuleEngine` community defaults now run **2 queries per receipt** (one `IN` query per type over all products) instead of 2·P — the per-product `findActiveDefaultRuleOwnersWhoBought` N+1.
- **Still open (smaller)**: the per-candidate `lastPaidPrice(productId, householdId)` lookup inside `fireCheaperMarket` is still one query per matching rule — fine at current volume, batch/cache before high write throughput. The 24h cooldown is per default-rule (coarse) — revisit if users want per-product granularity.

---

## Merchant segment classification depends on BrasilAPI (best-effort)
- **Now (built)**: `MarketLocation.segment` (UNKNOWN/SUPERMARKET/PHARMACY/OTHER) is verified from the CNPJ's **CNAE** via BrasilAPI (`/api/cnpj/v1/{cnpj}`, free, no auth) — `CnpjActivityClient` + `MarketLocationService.classifyPendingSegments()` (scheduled batch, decoupled from confirm, attempt-capped, name-pattern fallback in `MerchantClassifier`). When a market resolves to PHARMACY, OTHER products bought there are backfilled to the HEALTH category. **Best-effort by design**: a lookup failure leaves the segment UNKNOWN and categorization proceeds normally on the name guess (or just the dictionary) — never blocks ingestion.
- **Why OK for dev**: external dependency is fully optional (toggle `MERCHANT_CLASSIFY_ENABLED=false`), retried, and degrades gracefully. The name fallback covers the big chains immediately, so the CNAE layer is pure accuracy upside.
- **Watch before prod**: BrasilAPI has no SLA / rate limits we control — if it gets flaky at volume, consider (a) a paid CNPJ provider or (b) caching/import a CNAE dataset. Also the first receipt from a brand-new oddly-named pharmacy categorizes on the name guess until the async batch resolves the segment (then the backfill corrects it). Headless/cron envs without outbound internet will just leave segments UNKNOWN.
- `MERCHANT`-sourced categories are excluded from ML training (`TRUSTED_SOURCES`) and locked against admin recategorization downgrades — intentional, keep it that way.

---

## Multi-state SEFAZ coverage = 1 / 27 verified
- **Now**: only RS has a working end-to-end ingestion path (`SvrsSharedPortalAdapter` against `dfe-portal.svrs.rs.gov.br`, with real Zaffari/Bistek HTML fixtures). Submitting chaves from any other UF returns `UnsupportedStateException`.
- **What we know**: empirical probe of all 27 portals on 2026-05-06 documented in `docs/MULTI_STATE_RECON.md`. ~10 UFs are server-rendered + simple GET (Tier 1 — quick to add once we have real chaves). 3 UFs use JSF/ASP.NET ViewState (Tier 2, more code). 5 UFs gate behind captcha (Tier 4, needs 2Captcha or similar). 1-2 are SPAs requiring a headless browser.
- **Bottleneck**: not code, **data**. Adapters can't be written safely against synthetic chaves; each UF needs 1 real, recent (≤ 3 months) cupom HTML to develop selectors against. See `docs/MULTI_STATE_RECON.md` for the population coverage analysis (Tier 1 + Tier 2 = ~54% of Brazil) and recommended implementation order.
- **Fix when ready**: collect chaves via e-CAC, state cashback portals, or contacts in each state; save HTML in `src/test/resources/fixtures/sefaz/<uf>/`; implement adapter following `SvrsSharedPortalAdapter` template.

---

## Storage / infrastructure

### Profile picture storage = local disk (now on a persistent volume)
- **Now**: `LocalDiskProfilePictureStorage` writes to `PROFILE_PICTURE_DIR` (default `/tmp/economizai/profile-pics`). On the self-hosted server the app sets it to `/data/profile-pics`, backed by the named Docker volume `economizai-profilepics` (compose `app` service) — same durability as `economizai-pgdata`. Bytes served via `GET /users/me/profile-picture`.
- **History**: the default `/tmp` is **inside the container** and wiped on every `--build` redeploy. Since every push auto-deploys (rebuild), uploaded pics vanished while the DB key dangled → `read()` fell back to the initials avatar = pics "disappearing". Fixed 2026-06-07 by mounting a volume + pointing the env var at it.
- **Why still NOT prod-final**: local disk can't scale to multiple instances and isn't backed up off-box.
- **Fix before prod**: implement an `S3ProfilePictureStorage` (or Cloudinary), wire via the `ProfilePictureStorage` interface, switch via env var. ~2 hr.

### Push notifications = Expo Push Service (works in dev with no setup)
- **Now**: `PushDispatcher` calls the Expo Push HTTP API (`https://exp.host/--/api/v2/push/send`). The FE (React Native + Expo) registers an Expo Push Token via `PUT /api/v1/users/me/push-token`; the backend POSTs to Expo, which routes to FCM (Android) or APNs (iOS).
- **Why this stack and not firebase-admin**: the FE generates Expo tokens (`ExponentPushToken[...]`), which can't be sent through raw FCM. Expo also removes the need for service-account JSON, native config and SDK init.
- **Dev**: works out of the box — no env var, no project setup. Push reaches the device via Expo Go.
- **Prod (optional)**: set `EXPO_ACCESS_TOKEN` env var with a token from https://expo.dev → Account → Access Tokens. Raises rate limits and feeds the Expo analytics dashboard. Without it, sends still work but at lower throughput.
- **Prod (iOS)**: publishing to the App Store requires an Apple Developer Program membership ($99/year — Apple's fee, not Expo's) and uploading the APNs auth key to Expo (Expo handles the rest of the iOS push plumbing).

### SMTP email = disabled (impacts notifications + auth flows)
- **Now**: two paths consume SMTP:
  1. `EmailDispatcher` (notification channel) — `@ConditionalOnProperty(NOTIFICATIONS_EMAIL_ENABLED=true)`, defaults off, falls back to PUSH/NONE when off.
  2. `AuthEmailSender` (password reset + email verification) — always loaded; if SMTP isn't configured, **logs the link with `[DEV-MODE]` prefix** instead of sending. The reset/verify endpoints still return 204, so the FE flow works in dev — the developer copies the token from server logs.
- **Why OK for dev**: no SMTP creds, FE end-to-end testing still works (manually grab the link).
- **Why NOT OK for prod**: real users won't see a `[DEV-MODE]` log line. They get NO password-reset / verification email at all.
- **⚠️ Security gap**: in DEV-MODE the reset/verify **token is written in plaintext** to the persistent app log (`C:\economizai-data\logs\app\app.log`) and is visible in Dozzle. Anyone with log access can hijack any account mid-reset. Acceptable only because this is a single-owner dev box — but the DEV-MODE fallback must be **disabled in prod** (not just "SMTP configured"): once real email works, `AuthEmailSender` should never log the link. Tighten before prod.
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

## Hosting / access — self-hosted (replaced Render)

> **2026-06-03:** Render's free Postgres was **suspended** (free DBs get reaped),
> which took the web service down. We moved the dev server **off Render to a
> self-hosted machine**. The old `https://economiz-ai.onrender.com` URL is dead.

### How to run it
One file does both jobs via Compose profiles — `docker-compose.yml` at repo root.

- **Backend-dev machine** (iterate fast — Postgres in Docker, app via Maven):
  ```bash
  docker compose up -d db          # Postgres only on :5432
  ./mvnw spring-boot:run           # app on :8080
  ```
- **Server machine** (full stack for everyone):
  ```bash
  docker compose --profile server up -d --build   # Postgres + app
  docker compose logs -f app                       # watch boot / Flyway
  ```

Defaults mirror `application.yaml` localhost fallbacks, so it runs with no `.env`.
Drop a `.env` (gitignored) beside the compose file to override `DB_USERNAME`,
`DB_PASSWORD`, `JWT_SECRET` (`openssl rand -hex 64`), `CORS_ORIGINS`.
`ddl-auto: none` + Flyway → a **fresh empty volume rebuilds the whole schema** on
first boot. No data to restore (Render data is gone).

### How to access it
- **Base URL (API):** `http://<server-LAN-IP>:8080/api/v1`
- **Health:** `http://<server-LAN-IP>:8080/actuator/health` → `{"status":"UP"}`
- **Swagger:** `http://<server-LAN-IP>:8080/swagger-ui/index.html`
- Host `:8080` maps to the container's `:10000` (Dockerfile port). Postgres is on `:5432`.
- The FE machine must be on the same LAN; the server's OS firewall must allow `:8080`.
  Add the FE origin (and any public URL) to `CORS_ORIGINS`.
- For off-LAN / public access, front it with a Cloudflare Tunnel or ngrok → add that
  https URL to `CORS_ORIGINS`.

### Ops
- Stop / restart / wipe: `docker compose --profile server down` / `restart` /
  `down -v` (the `-v` drops the `economizai-pgdata` volume → fresh schema next boot).
- Services use `restart: unless-stopped`; ensure Docker starts on machine boot so the
  stack returns after a reboot. Data persists in the named volume.

### Windows server machine (2026-06-05) — LIVE
The self-hosted dev server runs on a **Windows 11** box, **full stack in Docker**,
never-sleep on AC, LAN-only. Static LAN IP: **`192.168.68.108`**.

- **API base:** `http://192.168.68.108:8080/api/v1`
- **Health:** `http://192.168.68.108:8080/actuator/health` → `{"status":"UP"}`
- **Swagger:** `http://192.168.68.108:8080/swagger-ui/index.html`

**Remote access:** a **Cloudflare quick-tunnel** exposes the API over HTTPS from any
network. The live public URL is always in `current-tunnel-url.txt` at repo root.
⚠️ The free `trycloudflare` URL **changes on every restart** — `start-tunnel.ps1`
auto-updates `.env` CORS and restarts the app each time, but the FE must read the new
URL from `current-tunnel-url.txt`. Upgrade to a **named tunnel** (stable URL) once a
domain is on Cloudflare.

Helper scripts at repo root (run each in an **Administrator** PowerShell once):
- `setup-firewall.ps1` — inbound allow rule, TCP 8080, Private profile.
- `set-static-ip.ps1` — pins the Wi-Fi adapter to `192.168.68.108/24` (gw `192.168.68.1`).
  Revert to DHCP: `netsh interface ip set address name="Wi-Fi" source=dhcp`.
- `make-always-on.ps1` — power (never sleep), Docker auto-start, container restart,
  stack watchdog (startup + every 5 min via `stack-watchdog.ps1`).
- `enable-autologin.ps1` — auto-login this local account so reboots are unattended
  (flips Win11 Hello-only flag, sets Winlogon keys). Box is home-only/non-critical so
  the stored-password tradeoff is accepted; BitLocker skipped.
- `start-tunnel.ps1` — launches cloudflared, captures URL, syncs CORS, restarts app,
  publishes URL to `current-tunnel-url.txt`. Runtime files (`*.log`, `current-tunnel-url.txt`)
  are gitignored.
- `setup-tunnel-autostart.ps1` — Scheduled Task to keep the tunnel up at logon.

**Logs / debugging:**
- **Dozzle** (live web UI): `http://192.168.68.108:9999` (LAN) — view/search/follow
  container logs in the browser, filtered to `economizai-*`. Runs as the compose `logs`
  service. (Not exposed via the public tunnel — LAN only.)
- **`logs.ps1`** helper: `.\logs.ps1` (tail+follow app), `-Errors` (WARN/ERROR only),
  `-Grep rcpt=xxx` (filter by req/rcpt/user/item id), `-Db` (database), `-Save` (snapshot
  to `C:\economizai-data\logs`), `-Since 30m`. Logs use the MDC pattern
  `req= user= rcpt= item=` so grep is powerful.
- **Data dir:** all runtime data (logs, db backups, images) lives in
  `C:\economizai-data` (override: `ECONOMIZAI_DATA_ROOT`) — OUTSIDE the project tree /
  runner checkout, so nothing is committed or OneDrive-synced.
- **File history:** `C:\economizai-data\logs\app-YYYY-MM-DD.log` (+ db), 14-day retention.
  Written daily 02:55 by the "economizai - daily log save" task (`setup-logsave-schedule.ps1`).
- **Disk safety:** Docker `json-file` log caps in compose (app 20MBx10, db 10MBx3) so a
  24/7 box can't fill the disk.

**Gotchas learned setting this up (so we don't relive them):**
- **`postgres:18` changed its data-dir convention.** Mount the volume at
  `/var/lib/postgresql` (the parent), NOT `/var/lib/postgresql/data` — PG18 sees the
  old path as an "unused mount/volume" and `initdb` refuses to run, leaving the
  healthcheck stuck `unhealthy`. Fixed in `docker-compose.yml`.
- **Orphaned Docker processes wedge the engine.** Symptom: Docker Desktop dialog
  "Cannot start server … open `\\.\pipe\dockerExtensionManagerAPI`: Access is denied",
  and `dockerd` runs but never opens its socket. Cause: a leftover `com.docker.extensions`
  (or duplicate `com.docker.backend`) from a prior start still holds the pipe. Fix: kill
  ALL `Docker Desktop` / `com.docker.*` / `docker-ai` processes, `wsl --shutdown`, then
  start a single clean instance.
- **`docker context` resets to `default` (dead `npipe:docker_engine`) on each Docker
  restart.** If `docker` commands hang, run `docker context use desktop-linux`.
- **WSL re-corrupted itself once** (`REGDB_E_CLASSNOTREG`) and self-repaired via
  `wsl --update`. If it recurs and a repair doesn't stick → `winget install Microsoft.WSL`.
- **Docker Desktop didn't auto-start the engine after a reboot (2026-06-06).** The GUI
  launched at logon but came up as a white/hung window; `com.docker.service` was Stopped
  (StartType=Manual) and stale `com.docker.backend` processes from boot held the pipe.
  The old "start Docker engine" task just ran `Docker Desktop.exe` once with no retry — a
  hung window counts as "running", so it never recovered. **Fixes applied:** (1) service
  set to **Automatic** start; (2) the logon task now runs **`start-docker-wait.ps1`**, a
  wait-for-healthy wrapper that polls `docker version` and relaunches a CLEAN instance (kill
  all docker procs + `wsl --shutdown` + restart service) up to 4 times. Recovery actions are
  logged to `C:\economizai-data\logs\docker-recovery.log`. Manual recovery if it ever wedges again: just run
  `start-docker-wait.ps1` (elevated, so it can kill the protected processes).

**Known weak spots (revisit):**
- **Mesh/extender network**: this box roamed `192.168.0.x` → `192.168.68.x` once. The
  Windows static IP is tied to the `192.168.68.x` node — if it roams to the other node it
  goes offline until reverted. Durable fix: **wired ethernet** or a **router DHCP
  reservation** by MAC `44-AF-28-2B-02-A5`.
- **Auto-start needs a user session**: Docker Desktop's WSL backend won't run with nobody
  logged in. `setup-autostart.ps1` triggers at logon, so after an unattended reboot the
  server is down until someone logs in. For true headless recovery, also enable auto-login
  (`netplwiz`) — tradeoff: stored password, anyone with physical access gets in.

### Before prod (whenever that comes)
- A self-hosted box on a home connection isn't a prod target (uptime, dynamic IP, TLS).
  For real users: managed Postgres that doesn't expire (Neon / Supabase free, or paid)
  + a proper host (Fly.io / Railway / paid Render / a VPS). Document the chosen path.

---

## Data correctness

### ML categorization gated OFF (dictionary-only for now)
- **Now**: `economizai.ml.category-apply-enabled=false`. The cascade applies dictionary entries only; the ML model is confidently wrong at current data volume (~hundreds of products), so its predictions aren't written. It's still trained + measured (shadow) via `/categorizer/benchmark`.
- **Why OK for dev**: dictionary-only is deterministic and currently 100% on the golden set; uncategorized is better than confidently-wrong.
- **Fix / revisit**: once `mlCategoryAccuracyPct` (shadow) is consistently high — after the catalog has thousands of trusted labels — flip `ML_CATEGORY_APPLY_ENABLED=true` and watch the benchmark. Track via `/categorizer/quality/history`.

### User corrections are global (should be per-household) — see HELP.md "Planned"
- **Now**: `PATCH /products/{id}` mutates the shared canonical product; any authenticated user changes categories/brand for everyone, and it's not admin-gated.
- **Fix before real multi-user volume**: household-scoped overrides + corrections-as-votes (design in HELP.md). Interim: gate `PATCH /products/**` to ADMIN.

### IBGE municipality code missing
- **Now**: `PriceObservation` carries `city` (string from Nominatim) + `state` (UF) but not the IBGE 7-digit municipality code that the FE spec wanted (PRO-53/54).
- **Why OK for dev**: city + state is enough for "show me everything in Porto Alegre".
- **Fix before prod B2B sales**: load the IBGE municipality CSV (5,570 rows) into a lookup table, backfill the column on existing rows, derive on geocode. Important for regional aggregation and for matching against external datasets (IBGE's own published prices, IPCA, etc). ~3 hr.

---

## Billing / subscriptions

### No payment provider wired — PRO is granted manually / via webhook only
- **Now**: the PRO tier is fully **enforced** (gates in `SubscriptionGateService`, 402 on block), and a user can be made PRO two ways: (a) admin `PUT /api/v1/admin/users/{id}/subscription-tier {"tier":"PRO"}`, or (b) the provider-agnostic webhook `POST /api/v1/webhooks/subscription`. There is **no actual payment collection** — no Stripe/Mercado Pago/Pix integration, no checkout, no self-serve upgrade page.
- **Webhook secret is empty in dev**: `economizai.billing.webhook-secret` defaults to empty → the `X-Webhook-Secret` check is **skipped**, so anyone who can reach the route can grant/revoke PRO. Fine locally; **a hole in prod if left empty**.
- **Why OK for dev**: lets us test the entire gated experience (and demo PRO) without a payment processor or merchant account.
- **Before prod — to enable paid subscriptions**:
  1. **Pick a provider** (Stripe Brasil, Mercado Pago, or Pagar.me + Pix).
  2. **Get API keys** (publishable + secret) and create the product/price (R$9.90/mo per MONETIZATION §1).
  3. **Set `BILLING_WEBHOOK_SECRET`** (env → `economizai.billing.webhook-secret`) to a strong random value so the webhook rejects unsigned calls (401).
  4. **Point the provider's webhook** at `POST /api/v1/webhooks/subscription` and map its event payload onto our `{ userEmail, action: ACTIVATE|CANCEL, provider, providerRef, currentPeriodEnd }` shape, sending the shared secret in `X-Webhook-Secret`. (If the provider signs with HMAC instead of a static header, add a small verify step in `SubscriptionWebhookController` for that scheme.)
  5. **Build the checkout / self-serve upgrade flow** in the app + a `PUT /users/me/subscription` (or hosted-checkout redirect). Currently only admins can flip the tier from inside the app.
  6. Decide period-end handling: a scheduled job should expire `current_period_end` PRO subs back to FREE if the provider stops sending renewals (not built).

---

## Monitoring / ops

### `/actuator/prometheus` is public (no auth)
- **Now**: `/actuator/prometheus` is exposed and `permitAll`'d so a scraper can hit it without credentials. Leaks JVM stats, request counts per route, error rates, GC timings.
- **Why OK for dev**: nobody is scraping yet, the data is only useful with context, and no scraper SDK natively does JWT bearer auth.
- **Fix before serious traffic**: tighten via one of (a) basic auth on the actuator chain, (b) IP allowlist to the scraper's egress, (c) a separate management port not exposed to the internet, (d) a dedicated `metrics-scraper` service account behind the existing JWT filter. ~30 min.

### Logs go to stdout only — no aggregation
- **Now**: Render captures stdout. Searchable in their dashboard but no retention beyond the free-tier window.
- **Fix before serious ops**: ship logs to BetterStack, Loki, or Papertrail. Render has add-ons for this. ~1 hr.

### Read caches (dashboard / insights) are in-process
- **Now**: `dashboard` (2 min) + `insightsSpend` (5 min) are cached in-memory via Caffeine (`CachingConfig`), keyed by household with a per-household generation counter (`HouseholdCacheGen`) bumped on receipt mutations. ETags via `ShallowEtagHeaderFilter`.
- **Why OK for dev**: single instance, so one in-process cache is the whole truth; generation counter resets on restart (at worst one recompute).
- **Why NOT OK at scale**: multi-instance would give each node its own cache + its own generation map → a mutation on node A wouldn't invalidate node B's cache (stale reads up to TTL). The ETag filter is shallow (still computes the body), so no compute savings either.
- **Fix before multi-instance**: move the cache + generation counter to a shared store (Redis). ~half a day. Single instance is fine until then.

---

## Last-checked: 2026-06-06

When you take care of an item above, **delete it from this file** instead
of marking it done — keep the file lean so what remains is what's
actually outstanding.
