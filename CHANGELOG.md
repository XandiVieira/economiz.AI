# economizai — what's new

FE-facing diary of meaningful backend changes — new endpoints, response-shape
changes, behavior changes, gotchas worth knowing about. **Newest at the top.**
Skim from the top until you hit a date you've already read.

For the complete API contract see [API.md](./API.md) (walk-through) or
`/swagger-ui` on whichever environment you're hitting.

**Dev server (use from anywhere):**
- API: `https://economizai.economizai.workers.dev/api/v1`
- Swagger: `https://economizai.economizai.workers.dev/swagger-ui/index.html`
- Health: `https://economizai.economizai.workers.dev/actuator/health`

---

## 2026-06-30 — Chave de acesso manual + suporte a CAPTCHA (MS)

### Input manual da chave de acesso

`POST /api/v1/receipts` agora aceita dois formatos no campo `qrPayload`:

- **QR code URL** — como antes (ex: `https://dfe-portal.svrs.rs.gov.br/...`)
- **Chave de acesso bare** — os 44 dígitos numéricos impressos na nota, com ou sem espaços

```json
{ "qrPayload": "50260677863223012709650190004048511190344086" }
// ou com espaços (stripped automaticamente pelo backend):
{ "qrPayload": "5026 0677 8632 2301 2709 6501 9000 4048 5111 9034 4086" }
```

Útil quando o QR code não consegue ser lido (nota amassada, mal impressa, etc.). O código de 44 dígitos está sempre impresso abaixo do QR na seção "CHAVE DE ACESSO".

### Suporte a CAPTCHA (MS e futuros estados)

O backend agora resolve automaticamente o reCAPTCHA v2 do portal SEFAZ-MS via CapSolver antes de buscar o DANFE. Transparente para o FE — o fluxo de submit/polling não muda. Estados que ainda não usam CAPTCHA não são afetados.

Novo código de erro possível ao submeter uma nota de estado com CAPTCHA não configurado (improvável em prod, mas útil saber):
- `503` com `messageKey: receipt.captcha.unavailable` — solver não habilitado para esse estado
- `502` com `messageKey: receipt.captcha.failed` — solver falhou após retries (saldo CapSolver esgotado, por ex.)

---

## 2026-06-29 — EAN catalog: step A2 no cascade de canonicalização

Nova camada entre o lookup interno de EAN (A1) e o dicionário de keywords (A3):

```
POST /api/v1/categorizer/ean-catalog/import
  body: [{"ean":"7894900010015","genericName":"Coca-Cola","brand":"Coca-Cola",
          "category":"BEVERAGES","source":"OPEN_FOOD_FACTS"}, ...]
  → { imported: N, skipped: M }
  Bulk-upsert de EANs na tabela ean_catalog. source aceita:
  OPEN_FOOD_FACTS | CURATED_IMPORT | USER_CONFIRMED
```

- Quando um novo EAN aparece num NFC-e, o sistema agora verifica a tabela
  `ean_catalog` antes de cair no dicionário de keywords — lookup O(1) por EAN.
- Se encontrado, a categoria e o nome genérico do catálogo enriquecem o produto
  criado (categoria do catálogo tem precedência sobre o dicionário de keywords
  para itens com EAN real).
- Tabela seed-ável via Open Food Facts: baixar o dump Brasil e importar via
  o endpoint acima. Nenhuma dependência externa em runtime.
- Requer `Role.ADMIN`.

---

## 2026-06-28 — Categorizer: CONSENSUS source, admin reset/import endpoints, expanded dictionary

### New `CONSENSUS` categorization source

Products graduated by the consensus promotion job now carry `source=CONSENSUS` (was `USER`). This lets the two origins be distinguished in queries and resets. `CategorizationSource` enum values: `NONE`, `DICTIONARY`, `LEARNED_DICTIONARY`, `ML`, `MERCHANT`, `USER`, `CONSENSUS`.

### Four new admin endpoints

```
GET /api/v1/categorizer/consensus
  → [{ id, ean, normalizedName, genericName, brand, category }, ...]
  Lists all products the consensus job graduated (source=CONSENSUS).
  Review and approve/reject before deciding to revert.

DELETE /api/v1/categorizer/learned
  → { removedEntries: N }
  Wipes every auto-promoted learned dict entry and resets the in-memory
  dictionary to the curated CSV seed.  Use when the learned dict drifts.

DELETE /api/v1/categorizer/consensus
  → { revertedProducts: N }
  Reverts all CONSENSUS-graduated products to source=NONE / null category
  so they re-enter the cascade on the next request.  Does NOT clear the
  learned dict — run DELETE /learned too for a full restore.

POST /api/v1/categorizer/dictionary/import
  body: [{ "token": "milho", "genericName": "Milho", "category": "GROCERIES", "sampleCount": 999 }, ...]
  → { imported: N, skipped: M }
  Bulk-upserts token→category mappings directly into the learned dict and
  swaps the in-memory reference immediately.  sampleCount defaults to 999.
```

All four require `Role.ADMIN`.

### Product dictionary expanded (396 → ~700 entries)

Added coverage for OTHER, CLEANING, PERSONAL_CARE, HEALTH, BEVERAGES, BAKERY, MEAT_DAIRY, and PRODUCE categories. Curated CSV seed lives at `src/main/resources/seed/product-dictionary.csv`.

---

## 2026-06-28 — Household data merge on join/leave (behind a flag, dark)

Joining/leaving a household can now optionally **bring your data with you** and
**restore it when you leave**. **Shipped OFF** (`economizai.households.merge-enabled`
=false) — no behavior change until enabled, but the contract is here so the FE can
build against it.

- **`POST /households/join`** body gains two optional fields:
  - `bringData` (bool) — merge your data into the household you're joining.
  - `mergeCategories` (string[]) — which categories to bring; omit/empty = all.
    Values: `RECEIPTS, SHOPPING_LISTS, MANUAL_PURCHASES, CONSUMPTION_SNOOZES,
    CATEGORY_OVERRIDES, CUSTOM_CATEGORIES, PRODUCT_ALIASES, MARKET_ALIASES,
    BRAND_PREFERENCES`. On a conflict (same item already in the target), the
    target's copy is kept; yours is parked and restored if you later leave.
  - Both optional — omitting them = today's behavior (membership only).
- **`POST /households/leave`** — when merge is enabled, you return to your original
  data (restored automatically). No body change yet.
- **Consent (for taking a partner's data on split):** new endpoints
  - `GET /households/consents/pending` — requests awaiting MY approval.
  - `POST /households/consents/{id}/approve` — let the requester copy my data.
  - `POST /households/consents/{id}/deny` — refuse.
  - All return `ConsentResponse { id, requesterId/Name, grantorId/Name, scope,
    status, expiresAt }`; 400 on not-mine / already-resolved / expired.

---

## 2026-06-23 — Password reset is now a 3-step CODE flow (was a link) ⚠️ FE change

Password reset no longer emails a link — it emails a **6-digit code** the user
types into the app. Better for mobile (no deep-link/URL handling). **Three steps:**

```
1. POST /api/v1/auth/forgot-password    { "email" }                         -> 204 (always; emails the code)
2. POST /api/v1/auth/verify-reset-code  { "email", "code" }                 -> 204 valid · 400 invalid/expired/used
3. POST /api/v1/auth/reset-password     { "email", "code", "newPassword" }  -> 204 done · 400 invalid/expired/used
```

- **Step 2 is optional but recommended** — validate the code (without consuming it)
  to gate the new-password screen, so the user learns the code is wrong *before*
  typing a password.
- The code is **single-use**, **expires in 60 min**, and requesting a new one
  **invalidates the previous** code.
- **Breaking:** `reset-password` body changed from `{ token, newPassword }` to
  `{ email, code, newPassword }`. The old link-style `?token=` flow is gone.
- `forgot-password` still always returns 204 (no email enumeration).

(Full infra + links: [INFRASTRUCTURE.md](./INFRASTRUCTURE.md).)

---

## 2026-06-12 — receipts: captcha-gated states (MS) return a clear "coming soon" instead of failing

Mato Grosso do Sul's portal hides the receipt behind a reCAPTCHA, so we can't
read it until a captcha-solver provider is configured (a backend/ops decision,
not shipped yet). Until then, submitting an MS NFC-e returns **HTTP 503** with
message key `receipt.captcha.unavailable` ("NFC-e from this state requires a
captcha step we haven't enabled yet — coming soon"). The FE should treat 503 on
receipt submit as a soft "try again later / state not ready" state, distinct
from 400 (bad QR) and 502 (SEFAZ down). The whole solving path is built and
dormant; the day the key is set, MS just works with no API change.

---

## 2026-06-12 — receipts: Paraná (PR) NFC-e now supported 🎉

Second state live (after RS). PR's portal (`www.fazenda.pr.gov.br`) renders the
same DANFE layout the parser already understands — verified end-to-end with a
real Curitiba receipt. Scanning a PR QR code now works exactly like RS. Two
related fixes that also help RS:

- **QR URLs with raw `|` separators were being rejected** since the SSRF guard
  shipped (earlier today) — real QR payloads carry pipes in the query string.
  Fixed; full-URL payloads work again for all states.
- **Merchant-internal item codes are no longer stored as EANs**: some chains
  (e.g. drugstores) print a 6-7 digit internal code where others print the
  GTIN/EAN. Codes under 8 digits now leave `ean` null and product matching
  falls back to the description, preventing bogus cross-merchant product
  merges. Items from such receipts arrive with `ean: null` — already a normal
  case for the FE.

---

## 2026-06-12 — deals: DISMISSED/MUTED telemetry now drives a relevance filter (SHADOW mode)

The `DISMISSED` / `MUTED` events the app already reports via
`POST /notifications/events` now feed a per-user suppression filter for
`GET /deals` and the daily digest: mute hides the product everywhere (180d),
dismissal hides it for 14d — scoped to the **(product, market) pair when the
DISMISSED event carries `marketCnpj`**, product-wide when it doesn't (so send
`marketCnpj` on dismissals for the precise behavior).

**No visible change yet**: the filter launches in SHADOW mode (computed and
measured, never applied). It flips ON server-side once the validation report
shows it wouldn't hide deals users actually want — after that, dismissed/muted
deals genuinely disappear from the screen and digest for that user.

---

## 2026-06-12 — users: new `GET /users/me/subscription` (self-serve billing status)

Returns `{ tier, status, provider, currentPeriodEnd }` so the app can render
"PRO until {date}" and route "manage subscription" to the right store.
`status`/`provider`/`currentPeriodEnd` are `null` for FREE users who never
subscribed. Reminder of the billing model: purchases happen FE-side (App
Store / Play via RevenueCat) and the backend reflects them via webhook — there
is no purchase endpoint in this API.

---

## 2026-06-12 — receipts: QR codes carrying a non-SEFAZ URL are now rejected

Security hardening (SSRF guard). If the scanned QR payload is a full URL, the
backend now only fetches it when the host is an official SEFAZ domain
(`*.svrs.rs.gov.br` / `*.sefaz.rs.gov.br` for now). Anything else returns the
existing `400` with message key `receipt.qr.invalid` — the same error the FE
already handles for malformed QR codes. Payloads that are just the chave (the
common case) are unaffected.

---

## 2026-06-10 — insights: new `GET /insights/markets/top-discounts` (where you got the most discount)

New endpoint ranking the household's markets by the **discount received** there (biggest first), built on the receipt-level discount we now track. Markets that gave no discount are excluded.

```
GET /api/v1/insights/markets/top-discounts?from=&to=&limit=5
→ [{ cnpj, marketName, marketFriendlyName, grossTotal, discount, discountRate, receiptCount }, ...]
```
`discount` is R$ received; `grossTotal` is gross spend; `discountRate` = `discount / grossTotal` as a 0–1 fraction (format as %). Same date/limit params as `/insights/markets/top`. Personal (your household's receipts), so it works without community volume.

---

## 2026-06-10 — insights/dashboard: gross spend + a `discount` figure per slice

Follow-up to the discount change below. Spend totals stay **gross** (sum of item prices, internally consistent across every breakdown) and we now report the receipt-level discount **alongside**, aggregated for whatever slice you're showing. Net spend = `total − discount`, computed by the FE.

- **`GET /insights/spend`** (`SpendInsightsResponse`): new top-level `totalDiscount`; new `discount` on each `byMonth` / `byWeek` / `byMarket` bucket. `byCategory` buckets have **no** per-bucket discount (a receipt-level discount can't be split across categories) — use `totalDiscount` there.
- **`GET /insights/query`** (`InsightsQueryResponse`): new `summary.totalDiscount`; new `discount` on each bucket for **receipt-level** groupings (`DAY`/`WEEK`/`MONTH`/`YEAR`/`MARKET`/`CHAIN`). For `CATEGORY`/`PRODUCT` groupings `bucket.discount` is **`null`** — use `summary.totalDiscount`.
- **Dashboard** (`SpendSnapshot`): new `discount` for the current month.

No rateio: we never split a discount across items/categories. `discount` is only attached where each receipt falls wholly in the slice.

---

## 2026-06-10 — receipts: discounts are no longer distributed across items; new `discountTotal` field

**Behavior change (price accuracy):** we **stopped distributing** a receipt-level discount across item prices. Until now, when a receipt's items summed to more than what was paid, we spread the gap proportionally over every line — which silently distorted per-item prices (a promo on one item bled into all the others). Now **item prices are kept exactly as printed** on the NFC-e (`unitPrice` / `totalPrice` are gross), and the receipt's discount is tracked separately.

**New field:** `ReceiptResponse` and `ReceiptSummaryResponse` now carry `discountTotal` — the "Descontos R$" value as printed on the receipt, or `null` when the receipt declared no discount. Future use: surfacing things like "markets with the biggest discounts."

**FE impact — read this:** because items are now gross, `householdTotalAmount` (and any spend total derived from summing items) is now **gross of the discount**, so it can be **higher than `totalAmount`** (the amount actually paid). The relationship is: `sum(item totals) ≈ totalAmount + discountTotal`. If you show "what I spent," use `totalAmount` (paid) and render `discountTotal` as a separate "desconto" line; don't sum item prices and expect it to equal what was paid.

---

## 2026-06-09 — notifications: `destination` for deep-linking + discovery moved to the digest

**New field:** every `NotificationResponse` now carries `destination` — a routing hint derived from `type` so the FE knows which screen to open on tap (the id it needs is already in `payload`). Values: `DEALS` (`PROMO_PERSONAL`/`PROMO_COMMUNITY`/`CHEAPER_MARKET`/`DEALS_DIGEST`/`DIGEST`), `REPLENISHMENT` (`STOCKOUT`), `PRODUCT` (`PRICE_DROP`), `BUDGET` (`BUDGET`), `INBOX` (`SYSTEM` + fallback). See API.md §10b for the table.

**Behavior change:** discovery notifications (`PROMO_*` / `CHEAPER_MARKET`) are no longer pushed in real time on receipt confirm — they're now delivered **only via the daily deals digest**. Real-time pushes are now just the user's explicit alerts (`PRICE_DROP`, `BUDGET`). No FE action required, but expect discovery to arrive batched once/day rather than instantly.

---

## 2026-06-09 — notifications: `PRICE_ABOVE` rule type removed

We dropped the `PRICE_ABOVE` (price-ceiling) rule type. **FE: stop offering it** in the rule-creation UI — posting `type: "PRICE_ABOVE"` now fails validation (400) like any unknown type. Any existing `PRICE_ABOVE` rows are deleted server-side. The cheaper-market / deals features already cover the "don't overpay" use case. `PRICE_DROP`, `STOCKOUT`, `BUDGET` and the system defaults are unchanged.

---

## 2026-06-09 — Savings attribution (Phase D): "você economizou R$ X com as dicas"

New: the backend now closes the deals loop by attributing **realized R$ savings** to the deals we surface — the product's north-star metric.

- **New endpoint** `GET /api/v1/users/me/savings` (JWT, scoped to your household) → `{ "totalSavings": 42.50, "conversions": 7, "last30DaysSavings": 15.00 }`. Empty history → all zeros, 401 if unauthenticated. Use it for a "você economizou R$ X com nossas dicas" card.
- **`CONVERTED` is now tracked server-side, automatically.** When you confirm a receipt, the backend checks each purchased item against deals we surfaced to your household in the last **14 days**; a match becomes a conversion and records the realized savings `(previousLastPaid − paidUnitPrice) × quantity` (only when positive). No client action is required — the FE never posts `CONVERTED` (it's a server-only event type, as before).
- Attribution is **best-effort + correlational** (a recently-surfaced deal that the user then bought), never causal, and it never blocks or slows down a confirm.

## 2026-06-09 — Daily deals digest (Phase C): preferences + `DEALS_DIGEST` push

New: a scheduled daily rollup push that points to the deals screen, sent at most **once per day**, and only when there's something **new** worth telling the user (a brand-new deal, a meaningfully bigger discount, or a deal that lapsed past the lookback window). Standing, unchanged deals never re-notify.

- **Digest preferences** (JWT, scoped to you):
  - `GET /api/v1/users/me/digest-preferences` → `{ "frequency": "DAILY|WEEKLY|OFF", "sendHour": 0-23 | null }`.
  - `PUT /api/v1/users/me/digest-preferences` with the same shape. `frequency` is required; `sendHour` is optional (an override — `null` lets the backend infer your typical shopping hour). `sendHour` outside `0-23` → **400**. `frequency=OFF` is the master switch (no digest ever).
- **New inbox notification type `DEALS_DIGEST`.** It shows up in `GET /api/v1/notifications` like any other. Body reads e.g. `"Café 22% mais barato — e mais 3 ofertas pra você"` (singular/plural handled; no "e mais" when there's only one). Its `payload` carries a deep-link hint (`deeplink: "economizai://deals"`, `screen: "deals"`), `newsworthyCount`, and the best deal's `bestProductId` / `bestMarketCnpj` / `bestDiscountFraction`.
- **FE action:** tapping the digest should open the **deals screen** and fire `PUSH_OPENED` via `POST /api/v1/notifications/events` (key it with the notification id). Then it's the normal deals flow (`SCREEN_OPENED`, `DEAL_VIEWED`, `DEAL_TAPPED`).
- Send time defaults to ~16:00 (BR after-work) when we don't yet know your habits; otherwise it's inferred from your household's confirmed-receipt shopping hour. Timezone is **America/Sao_Paulo** for v1. WEEKLY currently goes out Thursdays.

## 2026-06-09 — "Ofertas pra você": new `GET /api/v1/deals` screen endpoint

New endpoint: `GET /api/v1/deals` (JWT, scoped to your household). The active, ranked list of discounts relevant to you right now — for each product your household buys, the best **currently-observed** community price at a relevant market that beats what you last paid by a meaningful margin.

- Params (all optional): `includeNearby` (default `false` = watched markets only; `true` also pulls in markets within `radiusKm` of home), `radiusKm`, `limit` (default 20; `limit <= 0` → `[]`).
- Each row carries `productId`, `productName`, `category`, `marketCnpj`, `marketName` (your friendly name), `currentPrice`, `lastPaidPrice`, `savingsAmount`, `savingsPct`, `discountFraction`, `distinctHouseholds`, `distanceKm` (nullable), `isWatched`, `observedAt`.
- Ranked by savings desc; trivial drops are filtered out (progressive bar: ~20% on cheap items, ~5% on pricey ones). K-anon protected — community price shows only when ≥ 3 households contributed. Returns `[]` when the index is off or nothing qualifies.

FE reminder: this endpoint is read-only and fires **no** telemetry. Fire `SCREEN_OPENED` when the screen opens, and `DEAL_VIEWED` / `DEAL_TAPPED` as the user scrolls/taps, via `POST /api/v1/notifications/events` — key them by the row's `productId` + `marketCnpj`. See API.md §7b.

---

## 2026-06-09 — Notification telemetry: report client-side engagement events

New endpoint: `POST /api/v1/notifications/events` (JWT, scoped to you) — body `{ "type": "...", "notificationId"?, "productId"?, "marketCnpj"? }`, returns `202`.

The app should fire one of these whenever the user engages with a notification or a surfaced deal:
- `PUSH_OPENED` — opened a push
- `SCREEN_OPENED` — opened the deals/notifications screen
- `DEAL_VIEWED` — a deal scrolled into view
- `DEAL_TAPPED` — tapped a deal
- `ADDED_TO_LIST` — added the deal's product to a shopping list
- `DISMISSED` — dismissed/swiped a deal or notification
- `MUTED` — muted a product/market/type

This is **telemetry only** for now — nothing changes in what you receive. We're capturing the engagement signal so a future ranking engine can make notifications smarter and more relevant (surface the deals you act on, suppress the ones you ignore). The more accurately the app fires these, the better that ends up. Server-only types (`SENT`/`DELIVERED`/`CONVERTED`) and unknown types are rejected with `400`.

---

## 2026-06-09 — Phone number + verification; SMS/WhatsApp notifications now deliver

New endpoints to set and verify a user phone:
- `PATCH /api/v1/users/me/phone` — body `{ "phoneNumber": "+5551999999999" }` (E.164). Stores the number (as unverified), generates a 6-digit OTP, and sends it via SMS. Returns `204`. Malformed/non-E.164 number → `400`.
- `POST /api/v1/users/me/phone/verify` — body `{ "code": "123456" }`. Correct + unexpired code marks the phone verified and returns `204`; wrong/expired/missing → `400`.

The `User` now has `phoneNumber` (E.164, nullable) and `phoneVerified` (boolean) on the backend. The **SMS** and **WHATSAPP** notification channels are now real: when the server has Twilio configured **and** the user has a verified phone, notifications routed to those channels actually deliver (via Twilio's Messages API). If Twilio isn't configured or the phone isn't verified, dispatch degrades gracefully (the in-app inbox row is still written, marked not-delivered with a `twilio_not_configured` / `phone_not_verified` reason) — no error to the user. ALEXA remains a stub.

---

## 2026-06-09 — Default notification rules backfilled for existing users

Default notification rules (PROMO_COMMUNITY, CHEAPER_MARKET, DIGEST, PROMO_PERSONAL) are now materialized for **all existing users** on startup, not just new signups / first time the screen is opened. `GET /api/v1/notification-rules` already seeds them lazily, so the response shape is unchanged — but accounts that never opened the screen will now have the toggles present immediately. Idempotent; triggers were already firing regardless (an absent default counts as enabled).

---

## 2026-06-08 — LGPD data export is now complete (response grew)

`GET /api/v1/users/me/export` now returns **all** of the user's personal data, not just account + household + receipts. New sections on `UserDataExportResponse`: `accountExtras` (push token, emailVerified/At, contributionOptIn), `notificationRules`, `watchedMarketCnpjs`, `subscription`, `marketAliases`, `customCategories`, `categoryOverrides`, `manualPurchases`, `shoppingLists`, and `notifications` (inbox). Purely additive — existing fields unchanged. (Account **deletion** — `DELETE /users/me` — was already complete: cascades all personal data, deletes the household when its last member leaves, and leaves anonymized price data intact per LGPD.)

---

## 2026-06-08 — RevenueCat billing webhook + subscription expiry (apps are PRO-ready)

The mobile PRO flow is now backend-complete. The apps integrate **RevenueCat** (one SDK over Apple StoreKit + Google Play Billing); when a purchase/renewal/expiration happens, RevenueCat calls **`POST /api/v1/webhooks/revenuecat`** and we sync the user's tier automatically.

- **FE/RevenueCat config (the only thing needed):** set RevenueCat's `app_user_id` to **our user's UUID** (preferred) or email so the webhook resolves the right account, and set the webhook **Authorization header** value (matched against `REVENUECAT_WEBHOOK_AUTH`; empty = endpoint disabled/fail-closed).
- Purchases/renewals → PRO until the period end; expirations → FREE; a bare cancellation keeps access until the period actually lapses.
- New **safety-net sweep** downgrades a PRO whose paid period elapsed without a renewal (failed payment/provider hiccup), so no one stays PRO for free.
- Nothing changes for the FE's PRO checks — keep reading `subscriptionTier` from the user. Web (Mercado Pago/PIX) is the remaining piece, pending your provider choice.

---

## 2026-06-08 — validation hardening from a full API audit (behavior fixes)

Four small behavior/validation fixes surfaced by an end-to-end endpoint audit:
- **`GET /api/v1/receipts` now accepts a `status` filter** (`PENDING_CONFIRMATION|CONFIRMED|REJECTED|FAILED_PARSE`, optional). An **invalid value now returns 400** (it was previously ignored). Absent = all statuses, as before.
- **Shopping-list items enforce "exactly one"** of `productId`/`freeText`: sending **both** now returns **400** (previously wrongly accepted); neither still 400; exactly one still 201.
- **`POST/DELETE /api/v1/markets/watched/{cnpj}`** now returns **400** for a malformed CNPJ (was 404) and accepts formatted CNPJs (strips `.`/`/`/`-`) — consistent with the rename endpoints.
- **Rate-limit `429` body** now matches the standard error shape (ISO-8601 string `timestamp`, UTF-8) instead of an array timestamp; `Retry-After` header unchanged.

---

## 2026-06-07 — market rename now validates CNPJ (400 instead of 500)

`PUT/DELETE /api/v1/markets/{cnpj}/name` used the `{cnpj}` path value as-is.
A malformed CNPJ (e.g. a wrong-length value) crashed with a **500** when it
overflowed the DB column. Now the CNPJ is normalized (formatting like
`12.345.678/0001-99` is accepted and stripped) and a value that isn't exactly
14 digits returns **400 `market.cnpj.invalid`**. **FE action:** none, unless you
were sending formatted/invalid CNPJs — those now get a clean 400.

## 2026-06-07 — PRO tier mechanism (gates DORMANT by default), admin set-tier, billing webhook

The FREE/PRO subscription tier (`User.subscriptionTier`) has a complete gating mechanism behind a single `SubscriptionGateService` — but **enforcement is OFF by default** (`SUBSCRIPTION_ENFORCE=false`). **Nothing is gated today: every feature is allowed for all users.** There are **no 402s** and no history/limit clamps until monetization launches; flip `SUBSCRIPTION_ENFORCE=true` to activate the caps below. When enforced, a FREE user hitting a PRO gate gets **HTTP 402 `subscription.upgrade_required`**.

**Gates when enforcement is ON (FREE limits; PRO = unlimited):**
- **Watched markets** — FREE pins up to **3** markets. Pinning a 4th → 402. Unpin / re-pin of an existing one is always allowed.
- **Receipt uploads** — FREE may submit **5 receipts per calendar month** (counts ALL statuses to prevent reject/resubmit gaming). The 6th `POST /receipts` → 402.
- **History window** — FREE analytics/history is clamped to the last **90 days**. Affects `GET /insights/spend`, `/insights/query`, `/insights/categories|markets/top`, `/insights/products/{id}/price-history`, and `GET /items`: a `from` older than 90d (or omitted) is silently floored. PRO unaffected. (No error — results are just windowed.)
- **Notification delivery** — FREE always gets the in-app inbox row, but push/email **dispatch is skipped** (audit row `delivered=false`, `failureReason=free_tier_inbox_only`). PRO gets push/email.
- **Basket optimization** — `POST /shopping-list/optimize` is PRO-only → 402 for FREE.

**New endpoints:**
- `PUT /api/v1/admin/users/{id}/subscription-tier` (ADMIN) — body `{ "tier": "PRO" | "FREE" }`; returns the admin user detail. PRO activates a manual subscription, FREE cancels it.
- `POST /api/v1/webhooks/subscription` (public; provider-agnostic) — body `{ "userEmail", "action": "ACTIVATE"|"CANCEL", "provider", "providerRef", "currentPeriodEnd" }`. Verified by the `X-Webhook-Secret` header against `economizai.billing.webhook-secret` (**fails closed**: when the secret is unset the webhook rejects everything; wrong secret → 401; constant-time compare; unknown user → 200 no-op). This is the seam a real payment provider (Stripe / Mercado Pago) maps its webhook onto.

**Heads-up for FE:** today nothing is gated (enforcement off) — no 402s, full history for everyone. Build the **402 → "show upgrade prompt"** handling now (distinct from 403) so the FE is ready when enforcement flips on; at that point FREE dashboards will window to 90 days.

## 2026-06-07 — Category lens: one product = one category, household vs global view

`GET /items`, `GET /insights/categories/top`, and `GET /insights/query` now take a **`categoryView`** param (`HOUSEHOLD` default, or `GLOBAL`). Each product belongs to **exactly one** category per household — **no double-counting**.

- **HOUSEHOLD (new default):** the effective category is the household's override (custom name or corrected enum) when set, else the global `Product.category`. `?category=GROCERIES` now returns/buckets products whose *effective* category is GROCERIES and **excludes** any product moved to a custom category or a different enum. Category insight buckets are re-aggregated by effective category (custom-category spend becomes its own bucket).
- **GLOBAL:** old behavior — filter/group purely by `Product.category`. Pass `categoryView=GLOBAL` to reproduce pre-lens numbers.

**Response additions (additive, nullable):**
- `PurchasedItemResponse` gains **`globalCategory`** (always the global enum, or null) alongside the existing effective `category`.
- `CategoryBucket` gains **`label`** (always present, display-safe); `category` is now **null** for custom-category buckets in the household view.

**Heads-up:** the default for these three endpoints changed from global to household. If a chart depended on the old global grouping/filtering, add `&categoryView=GLOBAL`.

---

## 2026-06-07 — Sign in with Google & Apple (new endpoints)

Social login is in. The app does the **native** Google/Apple sign-in, then posts the provider token:

```
POST /api/v1/auth/google  { "idToken": "..." }
POST /api/v1/auth/apple   { "identityToken": "...", "name": "Maria Silva" }
```

Both return the **same** `{ token, refreshToken, user }` as password login, so the FE flow after sign-in is identical. The backend verifies the token against the provider's keys; first-time users get a solo household + `emailVerified=true` (no verification email), and an existing local account with the same email is linked. `name` is only needed on the first Apple sign-in. Bad token → `401 auth.oauth.invalid`. Requires the backend to be configured with the app's Google/Apple client IDs (see infra) — without them the audience check is skipped (dev only).

---

## 2026-06-07 — household product list, "where is it cheapest", and custom market names (new endpoints)

Three additions for the products/markets screens:

- **`GET /api/v1/products/mine`** — the products **your household actually buys** (not the global catalog), newest purchase first, each with `timesBought`, `lastBoughtAt`, `lastUnitPrice`, and last market. Use this for the "my products" screen; keep `GET /products` for global autocomplete.
- **`GET /api/v1/products/{id}/markets?includeNearby=&radiusKm=`** — "onde está mais barato": one row per market (your watched markets always; nearby only if `includeNearby=true`), cheapest first, with `price`, `distanceKm`, `watched`/`visited`. **Privacy:** `priceType` is `OWN_LAST` (your exact last paid price at a market you've shopped at) or `COMMUNITY_MEDIAN` (k-anon median, only when ≥3 households contributed — sub-K markets are omitted, never shown with a single price). Full rationale in API.md §5.
- **`PUT/DELETE /api/v1/markets/{cnpj}/name`** — give a market a **household-only custom name**. The original `name`/`marketName` is **never overwritten**: every market-bearing response now also carries a **`friendlyName`** field that defaults to the original and is replaced by your rename when set. **Display `friendlyName`.** Applies for your household everywhere a market appears (markets list, product-markets, receipts, items, insights, price history, notifications); the global name and other households are untouched.

---

## 2026-06-07 — profile pictures now persist across redeploys (bugfix)

Uploaded profile pictures were disappearing after a while — the cause was that
the server stored them in a container-local `/tmp` dir that gets wiped on every
deploy, while the DB kept pointing at the now-missing file (so the API silently
served the initials-avatar fallback). Pics are now kept on a persistent volume
on the server, so an uploaded picture survives deploys. No API/payload changes —
`PUT/GET/DELETE /users/me/profile-picture` are unchanged. **FE action:** none.

---

## 2026-06-07 — notifications: user-created rules + toggleable defaults (new endpoints)

Big notification upgrade. There's now **one place to create and toggle every notification**: `GET/POST/PATCH/DELETE /api/v1/notification-rules` (full contract in [API.md §10f](./API.md)). `GET` returns the user's rules **and** the system defaults as toggleable entries (`isDefault: true`, `active`), so a single settings screen can drive everything.

New **user-creatable** rule types: `STOCKOUT` (replenishment — we predict a regularly-bought product is about to run out from your buying cadence and warn you N days ahead), `BUDGET` (monthly household spend cap). `PRICE_DROP` ("avise-me quando") is unchanged. New **defaults** you can turn on/off: `CHEAPER_MARKET`, `PROMO_COMMUNITY`, `DIGEST` (weekly summary), plus the existing `PROMO_PERSONAL`.

`CHEAPER_MARKET` = "someone bought a product I buy, at one of **my watched markets**, cheaper than I last paid." By default it only watches your favourite markets; set `radiusKm` on the rule to also include markets near home. The required drop scales with price (≈20% on a ~R$1 item → ≈5% on a ~R$200 item).

**Channels:** push (live) and email (live once SMTP creds are set) work; `ALEXA`, `SMS`, `WHATSAPP` are scaffolded (selectable, but dispatch is a no-op stub for now). A per-rule `channel` overrides your per-type preference.

**Migration note:** `/api/v1/alerts` still works exactly as before — it's now a thin alias over `PRICE_DROP` rules. The `NotificationResponse.type` set grew (see §10b); `PRICE_DROP` payload key renamed `alertId` → `ruleId`.

---

## 2026-06-07 — pharmacy detection now CNAE-verified (backend accuracy)

The pharmacy-merchant signal behind `HEALTH` categorization is now **verified from the CNPJ's CNAE** (economic activity) via an external registry — `4771*` = pharmacy, `4711*/4712*` = supermarket — instead of only guessing from the merchant name. It runs async/best-effort (never blocks import; falls back to the name guess if the lookup fails), and when a merchant is confirmed a pharmacy, its previously-`OTHER` items are backfilled to `HEALTH`. No API contract change — purely better category accuracy on items the FE already reads.

---

## 2026-06-07 — new `HEALTH` category

Added a 9th global category **`HEALTH`** (PT label "Saúde") for drugstore/health items — vitamins, meds, supplements, first-aid. The category enum is now: `GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, HEALTH, OTHER`. Add a `HEALTH` chip/label to your category map.

Two-layer classification, item-first: (1) the **product dictionary** tags meds/supplements/dosage-forms as `HEALTH` wherever bought (so a vitamin at a supermarket is caught too); (2) for items the dictionary can't place, a **pharmacy-merchant fallback** — if the receipt's merchant is a drugstore, the unknown item defaults to `HEALTH` instead of `OTHER`. Items the dictionary already knows keep their category (candy/cleaning bought at a drugstore stay correct). No contract change beyond the new enum value.

---

## 2026-06-07 — `productId` on receipt items

`ReceiptResponse.items[*]` now includes **`productId`** (`uuid`, or `null` for an unmatched item). This lets the **review screen drive category migration directly**: collect the `productId`s of the items the user checks and `POST /categories/migrate` — no need to cross-reference `/items` first. Additive field, nothing else changed.

---

## 2026-06-07 — SEFAZ fetch auto-retry + training endpoints locked to ADMIN

- **NFC-e import now auto-retries** transient SEFAZ failures (the SVRS portal is flaky — 5xx/timeouts/empty body). Up to 5 attempts total: immediate retry, then 5s/5s/5s. 4xx (bad chave) is **not** retried. Net effect for the FE: fewer spurious `502 receipt.sefaz.fetch.failed` — the user no longer has to keep re-submitting. Trade-off: a submit can take up to ~15s+ when the portal is down, so keep your loading state patient before showing an error.
- **Training/catalog-mutating categorizer endpoints are now ADMIN-only** (`POST /categorizer/retrain`, `/auto-promote`, `/promote-consensus`) — a normal user gets `403`. Read/debug GETs (`classify`, `ml/predict`, `benchmark`, `quality/history`, `status`) are unchanged.

---

## 2026-06-07 — custom categories + product migration (new screen)

Households can create their own categories (e.g. "Frutas") and migrate products into them — household-scoped, the global product/catalog is untouched.

- `GET /api/v1/categories` → all categories the household can use: the 9 global enums (`{id:null, name, custom:false}`) + the household's custom ones (`{id, name, custom:true}`).
- `POST /api/v1/categories` `{ "name": "Frutas" }` → 201 create (idempotent on name).
- `DELETE /api/v1/categories/{id}` → remove a custom category (its product overrides revert).
- `POST /api/v1/categories/migrate` `{ "productIds": [...], "targetCategory": "GROCERIES" | null, "targetCustomCategoryId": "<uuid>" | null }` → moves the selected products into the target (exactly one target). Household-scoped.
- The migration UI flow: list a category's items with `GET /items?category=GROCERIES` (existing), let the user check items, then `POST /categories/migrate`. View a custom category's items with `GET /items?customCategoryId=<uuid>`.
- **Heads-up:** an item's `category` field can now be a **custom-category name** (not just an enum) when the household has migrated it. Treat it as a display string.

---

## 2026-06-07 — brand registry expansion + brand backfill

Filled in lots of brands. Expanded the brand registry with ~45 brands found across the real catalog (Spaten, Andorinha/D'Aguirre, Coqueiro, McCain, Piracanjuba abbrev, Q-Boa, Limpol, Três Corações, …). New admin op `POST /api/v1/admin/products/refresh-brands` re-runs brand extraction over the catalog and **fills products missing a brand** (never overwrites an existing one) — needed because brand, like category, is set only at product creation. No FE change (the FE just sees more products with `brand` populated).

---

## 2026-06-07 — user corrections graduate to the learned dictionary (consensus)

The "evidence → truth" step. A single household's correction stays personal (as before), but when **enough distinct households correct the same product to the same category** (default ≥2), it graduates:
- the **global product** gets that category (so everyone sees it), and
- tokens that recur across consensus products (≥2, no disagreement) enter the **learned dictionary** — so similar future products inherit the category automatically.

This makes user feedback actually *teach* the deterministic system (cascade source #2), with no reliance on ML. Runs daily; manual trigger `POST /api/v1/categorizer/promote-consensus` (ops). No FE change.

---

## 2026-06-07 — user category correction (household-scoped "evidence, not truth")

Users can fix a wrong category on a receipt item. The fix is **per-household**: it changes what *your* household sees, and **never** mutates the global product (other households are unaffected). Each correction is also recorded as evidence/vote for a future cross-household consensus pass.

- **`PUT /api/v1/receipts/{id}/items/{itemId}/category`** body `{ "category": "MEAT_DAIRY" }` → 200, returns the updated `ReceiptResponse` with the corrected category applied. `400` if the item isn't linked to a product yet.
- The override is then applied wherever that household views the product's category: **`GET /receipts/{id}`** and **`GET /items`**. (Aggregates/insights still use the global category for now.)
- Categories are the fixed enum (`GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, OTHER`). Custom user categories are not supported yet.

---

## 2026-06-06 — dev tooling: full-chain trace + ML-only inspector

Developer endpoints for improving the algorithm (not FE-facing):
- `GET /categorizer/classify` (full chain) now also returns `mlApplied` — whether the ML guess is actually used live (currently false).
- `GET /categorizer/ml/predict?description=...` — the **ML model alone** (category + genericName + confidence), ignoring dictionary + the apply gate. For inspecting/teaching the model in isolation.

---

## 2026-06-06 — ML benched + brand/quantity quality tracking

- **AI categorization is gated OFF** in the live cascade (it was confidently wrong at current data volume). New items are categorized by the **dictionary only** (deterministic) or left uncategorized — no more confidently-wrong AI labels. The model is still trained and measured so we can switch it back on when it's good enough (env `ML_CATEGORY_APPLY_ENABLED`).
- **Quality tracking now covers brand + quantity too**, not just category. `GET /categorizer/benchmark` returns per-field accuracy (category / brand / quantity) plus a **shadow** ML accuracy (the model measured even while benched). `/categorizer/quality/history` snapshots now record `brandAccuracyPct`, `quantityAccuracyPct`, `mlAccuracyPct`.

No FE contract change.

---

## 2026-06-06 — categorization quality history (trend over time)

The quality metric is now **persisted**, so we can see if categorization is improving or regressing over time. A snapshot is written on every benchmark run and every backfill (`V31` table `categorization_quality_snapshots`).

- `GET /api/v1/categorizer/quality/history?limit=50` → snapshots newest-first: `{ recordedAt, trigger (BENCHMARK|BACKFILL), accuracyPct, benchmarkCorrect/Total, catalogProducts, catalogCategorized, catalogCoveragePct, mlReady }`.
- Each snapshot pairs **golden-set accuracy** (cascade correctness) with **catalog coverage** (% of real products that have a category) — so both "are our rules right" and "how much of the catalog is categorized" are tracked.

---

## 2026-06-06 — categorization quality: tracked metric + dictionary expansion

Product categories get more accurate, and we can now **measure** it.

- **Quality metric:** `GET /api/v1/categorizer/benchmark` runs the categorizer over a curated golden set and returns `accuracyPct` (+ the failing cases). Run it after each enhancement to see if we're improving. Dictionary-only accuracy is now **100%** on the golden set (was the goal of this pass).
- **Dictionary expanded** with the NFC-e abbreviations and compound terms that were mis-categorizing (`milho`, `batata frita`, `lays`/`salgadinho`, `bisc`, `ling`, `vh`, `abs`, `lav louca`, …). Compound phrases now win over bare tokens (so `batata frita` → GROCERIES, not PRODUCE via bare `batata`). This improves **new** scans going forward.
- **Backfill is gated for safety:** admin `POST /admin/products/recategorize` now applies **dictionary suggestions only by default** (the ML layer is currently unreliable — it was confidently mis-labeling, e.g. plates/glue → BAKERY). `?includeMl=true` to override. Existing products aren't changed until we run it.

(No FE contract change — categories just get better. The ML model quality is a separate follow-up.)

---

## 2026-06-06 — categorization dry-run / debug endpoint

New: `GET /api/v1/categorizer/classify?description=Milho&description=Lays` returns, for each term, **how the cascade would categorize it** — without persisting anything. Use it to debug wrong categories.

Each result has the final decision (`category`, `genericName`, `brand`, `packSize`, `source`) **plus a per-layer breakdown**: `dictionary` (what the curated/learned dictionary matched) and `mlCategory`/`mlGenericName` (the ML guess + `confidence` + `meetsThreshold`). The `source` field (`DICTIONARY` / `LEARNED_DICTIONARY` / `ML` / `NONE`) tells you which layer decided — so a wrong category is immediately traceable to a bad dictionary entry vs an over-confident ML guess.

---

## 2026-06-06 — `/receipts` category filter is now multi-value

`GET /receipts?category=` (and admin `GET /admin/receipts?category=`) now accepts **multiple** categories — `?category=GROCERIES&category=CLEANING` returns receipts matching either. **Backward-compatible:** a single `?category=X` works exactly as before. This aligns it with `/items` and `/insights/query`, which already took category lists — so the FE can use one category-filter component across all three.

---

## 2026-06-06 — server-side caching + ETags on /dashboard and /insights

Performance for the two heaviest home-screen calls. **No contract change** — same endpoints, same response shapes. What changed is how fast/cheap they are:

- **Server cache:** `GET /dashboard` (2 min) and `GET /insights/spend` (5 min) are cached per household. **Invalidated immediately** when you confirm/reject/delete/reparse a receipt or add/edit an item — so post-action data is never stale despite the TTL. Keep your front-end TTLs; they now compound with the server's.
- **ETags / `304 Not Modified`:** both endpoints return an `ETag` header. Send it back as `If-None-Match` on the next call — if nothing changed you get **`304` with no body** (saves bandwidth + parsing on silent background refreshes). Standard HTTP; most fetch libers handle it, but for `fetch()` note a 304 won't carry a body, so keep your last good payload cached client-side and reuse it on 304.
- **Unread badge stays live:** the dashboard's `unreadNotificationCount` is *not* cached — it's always current, so reading notifications updates the badge without waiting for cache expiry.
- Caveat: the dashboard's `communityPromosNearby` reflects network-wide activity, so it can be up to 2 min stale (bounded by TTL). Everything driven by your own actions is instant.

---

## 2026-06-05 — dev server moved to self-hosted LAN box

The old Render URL (`https://economiz-ai.onrender.com`) is **dead** (free DB reaped).
The dev backend now runs on a self-hosted Windows machine on the LAN.

- **API base:** `http://192.168.68.108:8080/api/v1`
- **Health:** `http://192.168.68.108:8080/actuator/health` → `{"status":"UP"}`
- **Swagger:** `http://192.168.68.108:8080/swagger-ui/index.html`
- **You must be on the same Wi-Fi.** Off-LAN access isn't available yet (ask for a tunnel).
- **Browser-based FE (incl. Expo Web):** your dev-server origin must be CORS-allowed —
  send your laptop's LAN IP to get it whitelisted. Native Expo Go on a phone needs nothing.

---

## 2026-06-06 — items endpoint (filter purchased items, e.g. by category)

New top-level resource: `GET /api/v1/items` returns your **purchased line items** flattened across all receipts, filterable and paginated. This is what to call for "tap a category → show every item I bought in it" (and "all purchases of product X", "items at market Y", etc.) — one endpoint, filters via query params.

It's the **item-level companion** to the two endpoints you already have:
- `/receipts` → receipt-level rows
- `/insights/query` → aggregates/rollups
- `/items` → individual line items ← new

**Filters (all optional, same vocabulary as `/insights/query`):** `from`, `to`, `marketCnpj[]`, `marketCnpjRoot[]`, `category[]`, `productId[]`, `ean[]`, `minReceiptTotal`, `maxReceiptTotal`, plus `page`/`size`. Multi-value filters OR within a dimension, AND across dimensions.

**Returns** `Page<PurchasedItemResponse>` — each row has item facts (`itemId`, `productId`, `category`, `displayDescription`, `quantity`, `unitPrice`, `totalPrice`, `nfcePromoFlag`, …) **plus the receipt context inline** (`receiptId`, `marketName`, `marketCnpj`, `purchasedAt`), so no second fetch is needed to render a list.

**Scope:** CONFIRMED receipts only, excluded items dropped (real purchases). Default sort `purchasedAt` desc. Empty = empty page, not 404.

Category values for the FE filter chips: `GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, OTHER`. No data-model change — items are still children of receipts; this is just a new read view. Full contract in API.md §4b.

## 2026-06-06 — price-drop alerts ("avise-me quando")

New feature: a user can ask to be notified when a product's price drops. The rule fires when **any household** in the network confirms a receipt that contributes an observation at or below the threshold — one person's receipt benefits another. This is the community retention loop.

**New endpoints — `/api/v1/alerts`:**
- `POST /alerts` → `201` with the created `PriceAlertResponse`. Body: `{ productId (UUID, required), thresholdPrice (BigDecimal, required), radiusKm (Double, optional), active (Boolean, optional — defaults true) }`. One rule per (user, product): re-posting the same product **updates** the existing rule (no duplicates, no 409).
- `GET /alerts` → `200` list of the caller's `PriceAlertResponse` (newest first).
- `DELETE /alerts/{id}` → `204`. `404 pricealert.not.found` if it isn't the caller's.

**`PriceAlertResponse` shape:** `{ id, productId, productName, thresholdPrice, radiusKm, active, lastFiredAt, createdAt }`.

**When a rule fires** it delivers a notification of the **new type `PRICE_DROP`** through the existing pipeline — it lands in `GET /notifications` (inbox) and is pushed if the user has a push token. Notification `payload` carries `{ alertId, productId, observedPrice, thresholdPrice, marketCnpj, marketName }`.

**Behavior worth knowing:**
- `radiusKm` is measured from the user's home (`homeLatitude/Longitude`). If set but either the home or the market has no coordinates, the rule **does not** fire (the geo constraint is honored, not ignored).
- A rule won't fire for the contributor's **own household** (you don't get pinged about your own receipt).
- **Cooldown:** a given rule fires at most once per 24h, so a flurry of cheap receipts won't spam the user.
- Only fires on receipts that actually contribute to the index (contributor opted in + master switch on). Opt-out receipts trigger nothing.

## 2026-05-08 — approximate-tax (IBPT) extraction on every NFC-e

Receipts now carry the IBPT-source approximate-tax disclosure that Brazilian merchants are required to print under Lei 12.741/2012. Surfaced so users can see the tax burden embedded in their groceries (Federal + Estadual taxes — ICMS, IPI, PIS, COFINS, IOF, …).

**`GET /receipts/{id}` — three new fields (all nullable)**:
- `approxTaxFederal` — federal portion (e.g. `15.13`)
- `approxTaxEstadual` — estadual portion (e.g. `13.73`)
- `approxTaxTotal` — sum, derived; `null` when both source fields are null

**`GET /receipts` and dashboard `recentReceipts` — one new field**:
- `approxTaxTotal` (BigDecimal, nullable) on each `ReceiptSummaryResponse`

**Important caveats** (please surface in the UX, not just the API):
- These are **estimates from the IBPT national table**, NOT taxes the consumer paid separately or what the merchant actually remitted. Label any number you show as `imposto aproximado` / `estimativa IBPT`.
- The line is legally mandatory but in practice not always present — small operators / Simples Nacional sometimes leave it blank or declare `R$ 0,00`. When the receipt's HTML doesn't carry the IBPT line, all three fields are `null`. **Aggregations should filter out `null`-tax receipts** before computing percentages, otherwise the average is diluted by missing data.
- Existing receipts in prod won't backfill — only newly ingested receipts after this deploy will have values populated. Old confirmed receipts stay null until reparsed.

---

## 2026-05-06 — multi-state recon documented (no behavior change)

Probed all 27 UFs' NFC-e portals empirically and saved the analysis under `docs/MULTI_STATE_RECON.md`. Bottom line: **end-to-end ingestion is verified for 1 UF (RS) today**; any other state still returns `UnsupportedStateException`. The doc maps each portal into tiers (simple GET / JSF stateful / XML / captcha / SPA / fetch issues) with effort estimates and a recommended implementation order.

The blocker is data, not code: writing adapters against synthetic chaves produces broken parsers because the response HTML for an invalid chave doesn't show the success-path layout. Real, recent chaves per UF unblock the work.

DEV_NOTES updated with the same status.

---

## 2026-05-05 — multi-state SEFAZ ingestion (no FE change)

`SefazAdapter.supportedState()` is now `supportedStates() : Set<UnidadeFederativa>` so a single adapter can claim multiple UFs. The existing RS adapter is renamed `SvrsSharedPortalAdapter` (the underlying portal hosts NFC-e for several states beyond RS) and now reads its UF list from config:

```
SEFAZ_SVRS_STATES=RS,SC,RJ,...
```

Default stays `RS`. To opt-in additional states without code: submit a real chave from that UF, verify the parser still extracts items, then add the UF to the env var. States with their own NFC-e portal (SP, MG, BA, PE, PR, GO, MT, MS, DF) still need a dedicated adapter — the SVRS URL won't serve their cupons.

Submitting a chave from a UF without any registered adapter still returns the same `UnsupportedStateException` as before.

---

## 2026-05-05 — admin: merge duplicate products

Catalog cleanup tool for cases the auto-dedup paths (alias / fuzzy / metadata) don't catch — the curator picks a survivor and absorbs another product into it.

### `GET /api/v1/admin/products/duplicates` (ROLE_ADMIN)
Returns groups of products that share an exact `(genericName, brand, packSize, packUnit)` profile. Each group is `{ genericName, brand, packSize, packUnit, category, products: [ProductResponse] }`. Within a group the oldest product comes first (sensible default survivor).

### `POST /api/v1/admin/products/{survivorId}/merge` (ROLE_ADMIN)
Body: `{ "absorbedId": "<uuid>", "dryRun": false }`. Migrates everything from `absorbed` into `survivor`:

- aliases, receipt items, price observations, manual purchases, shopping-list items → repointed.
- household aliases + consumption snoozes → conflict-aware (drops absorbed's row where survivor already has one for the household; UNIQUE (household_id, product_id) would otherwise fail).
- absorbed product deleted at the end.

Set `dryRun: true` to get the migration counts without applying. Returns `ProductMergeResultResponse` with per-table counts. **No undo** — the dry run is the only safety net.

---

## 2026-05-05 — admin: brand curation tools + bigger brand registry

### Brand registry expanded
`seed/brand-registry.csv` grew from 137 → 331 entries — covers the most common Brazilian grocery brands across rice/grains, pasta, dairy, chocolate, cookies, cleaning, margarine/oil, bakery, beverages, beer, wine, coffee, pet food, personal care, sweeteners, and ready meals. Existing products with `brand=null` are not auto-backfilled; new submissions match against the larger list automatically.

### `GET /api/v1/admin/products/missing-brand` (ROLE_ADMIN)
Paginated list of products without a brand. Response shape `MissingBrandProductResponse`: `{ id, ean, normalizedName, genericName, category, packSize, packUnit, sampleDescriptions: [string] }` — `sampleDescriptions` carries up to 5 raw descriptions from the product's aliases, giving the curator enough context to assign a brand without round-tripping.

### `PATCH /api/v1/admin/products/{id}/brand` (ROLE_ADMIN)
Lightweight PATCH — body `{ "brand": "Tio João" }`. Sets only the brand field. Returns the updated `ProductResponse`. Designed to be called from the missing-brand listing.

These two endpoints unblock manual catalog curation when brand extraction misses (see also: the metadata-dedup gate from this release, which becomes more effective as more products have brands).

---

## 2026-05-05 — metadata-based dedup for unknown EANs (no FE change)

When a previously-unseen EAN comes in, canonicalization now checks whether an existing product already matches by `(genericName, brand, packSize, packUnit)` before creating a new one. Catches the common case where small markets emit internal pseudo-EANs for the same physical product (mercadinhos, padarias, açougues do bairro) that would otherwise inflate the catalog.

Behavior:

- **Trigger**: item has an EAN, but the EAN isn't in the DB yet.
- **Match condition**: extracted `genericName`, `brand`, `packSize`, `packUnit` all non-null AND all four match an existing product. Any null → skip dedup, behave as before (create new product).
- **On match**: links the item to the existing product and persists the new description as an alias. The pseudo-EAN is intentionally **not** propagated onto the existing product — keeps `Product.ean` as a single canonical code.
- **Logged as**: `item.matched_by_metadata ean=<x> product=<id> brand=<y>`.

No FE-visible request/response change.

---

## 2026-05-05 — fuzzy alias matching (no FE change, better dedup)

Items without an EAN that previously fell through to `UNMATCHED` because their description was *almost* but not exactly equal to a known alias now get matched via Jaro-Winkler similarity. Concrete: `ARROZ TIO J 5KG` and `ARROZ TIO JOAO 5KG` are now recognized as the same product across markets.

Behavior:

- **Trigger**: only when the item has no EAN AND no exact alias hit.
- **Candidate pool**: aliases of products with the *same* extracted `(genericName, packSize, packUnit)` profile. Skipped if any of these is null — without that filter the search is too loose and false-positive-prone.
- **Threshold**: Jaro-Winkler ≥ 0.85.
- **On match**: links the item to the existing product and persists the new variant as an alias, so subsequent identical descriptions hit the cheap exact-alias path.
- **Logged as**: `item.matched_by_fuzzy product=<id> score=<n>`.

No request/response shape change. The `category`, `displayDescription`, etc. exposed on `ReceiptItemResponse` will populate more often as a result.

---

## 2026-05-05 — `DELETE /receipts/{id}` documented behavior (no FE change)

The endpoint already existed (still does, same path). Clarifying the LGPD invariant after a question came up:

- **Cascades**: receipt items + the audit-trail rows linking the household to its contributed observations.
- **Preserves**: anonymized `PriceObservation` rows themselves — once contributed, they stay in the community price index. This is enforced by the schema (FK `ON DELETE CASCADE` on audits, no FK from observations back to receipts).
- **Frees** the chave for re-import within the same household.
- 404 if the receipt belongs to another household.

No request/response shape change — just a contract guarantee written down so the FE knows what to expect.

---

## 2026-05-05 — push notifications go live via Expo Push Service

### `PushDispatcher` now talks to Expo
The dispatcher posts to `https://exp.host/--/api/v2/push/send`, the same path the Expo SDK uses internally. The FE (React Native + Expo) generates an `ExponentPushToken[...]` and registers it via `PUT /api/v1/users/me/push-token`; the backend POSTs that token plus the payload to Expo, which routes to FCM (Android) or APNs (iOS).

- **Why Expo and not firebase-admin direct**: Expo tokens are NOT raw FCM tokens — sending them through `FirebaseMessaging.send()` returns InvalidRegistration. The Expo HTTP API also avoids the firebase-admin SDK + service-account JSON setup entirely.
- **Auth (optional)**: setting `EXPO_ACCESS_TOKEN` env var raises rate limits and powers the Expo dashboard. Without it, sends still work for moderate volumes.
- **Token format**: tokens MUST start with `ExponentPushToken[` or `ExpoPushToken[` — anything else is rejected with `not an Expo push token` before any HTTP call.

### `POST /api/v1/admin/notifications/test` (ROLE_ADMIN)
On-demand test push — useful for verifying FE wiring without waiting for a natural trigger (promo, stockout, etc).

**Body:** `{ "email": "user@example.com", "title": "...", "body": "...", "type": "SYSTEM|PROMO_PERSONAL|PROMO_COMMUNITY|STOCKOUT" }`. `title`, `body`, `type` are optional and default to a canned system message.

Returns `202 Accepted`. Inspect the result via the inbox endpoint (`GET /api/v1/notifications`) on the target account, or look at the device.

---

## 2026-05-04 — receipt-level discounts now reflected in item prices

NFC-e item line totals don't always sum to the printed "Valor a pagar" — there can be a per-line or whole-bill discount that the parser was previously ignoring. From now on, when items don't sum to the receipt total (within R$ 0,05), the gap is **distributed proportionally across items** before persistence. So `unitPrice` and `totalPrice` on `ReceiptItemResponse` now reflect what the household actually paid, not the gross sticker prices. Knock-on effects:

- Per-product price history (`/insights/products/{id}/price-history`) is honest.
- The collaborative price index (`PriceObservation`) gets accurate per-unit numbers.
- Personal-promo detection compares apples to apples.

Existing receipts aren't backfilled. New submissions get the fix.

---

## 2026-05-04 — `category` exposed on receipt items

`ReceiptResponse.items[*]` now includes `category: string | null` — the `ProductCategory` of the linked Product (`GROCERIES` · `BEVERAGES` · `PRODUCE` · `MEAT_DAIRY` · `BAKERY` · `CLEANING` · `PERSONAL_CARE` · `OTHER`), or `null` when the item hasn't been canonicalized yet. Lets the FE render a category chip per item on `GET /receipts/{id}` without a per-item `GET /products/{id}` round-trip. Same pattern as `nfcePromoFlag` and `displayDescription`.

---

## 2026-05-04 — flexible insights query endpoint

### `GET /api/v1/insights/query` — one endpoint, any spend slice
Replaces the need to fan out across `/insights/spend` + `/insights/markets/top` + `/insights/categories/top` for cross-filtered views. Combine any subset of filters with a single `groupBy` dimension.

**Filters (all optional, list-typed where it makes sense):**
- `from`, `to` — date range (inclusive)
- `marketCnpj` — full CNPJs (repeat for multi-value: `?marketCnpj=A&marketCnpj=B`)
- `marketCnpjRoot` — chain-level (8-digit CNPJ root)
- `category` — `ProductCategory` values, list-typed
- `productId` — UUIDs, list-typed
- `ean` — EANs, list-typed
- `minReceiptTotal`, `maxReceiptTotal` — receipt-total range (BigDecimal)

**`groupBy`** (single dimension): `NONE` (default) | `DAY` | `WEEK` | `MONTH` | `YEAR` | `MARKET` | `CHAIN` | `CATEGORY` | `PRODUCT`. Temporal groupings sort ascending; non-temporal sort descending by total. `limit` caps bucket count (default 100, max 500).

**Response:** `{ filters, summary, groupBy, buckets }` — `summary` always present (total + receiptCount + itemCount + averageTicket); `buckets` populated when groupBy ≠ NONE. See API.md §4 for full shape and FE-friendly examples.

Backwards-compatible: existing `/insights/spend`, `/markets/top`, `/categories/top`, `/products/{id}/price-history` endpoints unchanged.

---

## 2026-05-03 (Tier 2 batch — admin endpoints, rate limiting, promo flag, unit normalization)

### Admin endpoints (ROLE_ADMIN only)
- **New: `GET /api/v1/admin/users?q=&page=&size=`** — paginated list of users with optional name/email substring search.
- **New: `GET /api/v1/admin/users/{id}`** — bundles user fields + household member count + receipt counts by status + 30-day spend total.
- **New: `GET /api/v1/admin/receipts?from=&to=&marketCnpj=&category=&q=&householdId=&page=&size=`** — cross-household receipt search. Same content-search semantics as the household-scoped `GET /receipts`. Includes `FAILED_PARSE` rows (useful for parser triage).
- **New: `GET /api/v1/admin/receipts/{id}`** — full receipt detail without the per-household ownership check.
- All four require a JWT for a user with `Role.ADMIN`. Regular users hit 403.

### Rate limiting (transparent — no API contract change)
- POST `/api/v1/auth/*` is now capped at **5 requests / minute / IP** (key = `X-Forwarded-For` first hop, falling back to `RemoteAddr`).
- POST `/api/v1/receipts` is now capped at **30 requests / hour / authenticated user**.
- Over-quota responses are `429 Too Many Requests` with a `Retry-After: <seconds>` header and a translated message body. Successful requests carry `X-RateLimit-Remaining`.

### Receipt items — NFC-e promo / discount flag
- `ReceiptItemResponse` now carries **`nfcePromoFlag: boolean`** — true when the SEFAZ HTML signaled the line was on promo (discount cell present, or description contains stems like "OFERTA", "PROMO", "DESCONTO", "COMBO", "LEVE 3").
- Backend behavior: promo-flagged items are **excluded from baseline calcs** in community-promo detection — comparing recent promos against historic promos was silencing real signals. Promo rows still count toward "current price".

### Price index — unit normalization
- New computed field on every `PriceObservation` written from now on: **`normalizedUnitPrice`** + **`normalizedUnit`** (one of `KG` / `L` / `UN`). Computed via the new `UnitConverter`, which translates Brazilian unit strings (`g`/`kg`/`mg`, `ml`/`L`/`lt`, `UN`/`CX`/`PCT`/`FD`) to a canonical base unit + multiplier.
- Community-promo detection now prefers `normalizedUnitPrice` when **all** observations in a (product, market) group have it. Mixed groups fall back to the raw unit price (current behavior). This fixes the false "huge price hike" we'd see when a market switched from 1L bottles to 2L bottles.
- Existing observations stay null until rewritten. No FE-visible field — purely an internal honesty fix for the panel queries.

---

## 2026-05-03 (Tier 1 batch — refresh tokens, search, reparse, manual brand, profile-pic polish)

### Auth — refresh tokens + logout (BREAKING for the auth response shape)
- **`AuthResponse` now carries `refreshToken`** alongside `token` on every register/login/refresh call. The FE must store both.
- **New: `POST /api/v1/auth/refresh`** `{ refreshToken }` → `{ token, refreshToken, user }`. Single-use rotation: every call returns a new pair, the old refresh token is consumed. Replay → `400 auth.token.invalid`.
- **New: `POST /api/v1/auth/logout`** `{ refreshToken }` → 204 (idempotent). Revokes the refresh token. Access token still valid until its 24h TTL expires — drop it client-side.
- Refresh token TTL: **30 days** (configurable via `JWT_REFRESH_EXPIRATION` env). Access token TTL unchanged at 24h.

### Receipts — content search
- **`GET /api/v1/receipts?q=leite condensado`** now does case-insensitive substring match against item raw description, friendly description, the linked product's normalized name, AND the receipt's market name. Combine freely with the existing date/category/marketCnpj filters.

### Preferences — manual brand override
- **New: `PUT /api/v1/preferences/brand/{genericName}`** `{ brand, strength }` → 204. Override the auto-derived dominant brand with your own choice. Strength is `PREFERRED` or `MUST_HAVE`.
- **New: `DELETE /api/v1/preferences/brand/{genericName}`** → 204. Clears the override.
- Override **wins over derived** in `GET /preferences`. The row keeps the historical `brandDistribution`, `sampleSize`, and pack-size fields (so the user still sees the signal) but `topBrand` + `brandStrength` come from the override.
- A manual override can also surface a generic the household hasn't bought yet (sampleSize=0).

### Profile picture — resize on upload + initials fallback
- **GET `/api/v1/users/me/profile-picture` never 404s anymore.** When no picture is uploaded, the server returns a deterministic 256x256 PNG with the user's initials on a color hashed from their email. Inspect the `X-Profile-Picture-Fallback: true|false` header to distinguish a generated avatar from an uploaded photo.
- **On upload**: JPEG/PNG are server-side downscaled to a 512px max dimension before storage. WebP is stored as-is. No FE work needed.

### Admin — reparse endpoint
- **New: `POST /api/v1/admin/receipts/{id}/reparse`** (ROLE_ADMIN only) — re-runs parsing on the stored raw HTML and resets the receipt to `PENDING_CONFIRMATION`. Used when a parser fix lands and we want to reprocess old receipts without forcing users to re-scan. Owner re-confirms to commit.

---

## 2026-05-02 (gap-closing batch)

### `GET /api/v1/dashboard` — bundled app-open snapshot
- One round-trip returns: current-month spend snapshot (total + count + ticket médio), last 5 confirmed receipts, top 5 suggested-list items, top 5 community promos in your area (watched markets bypass radius), unread notification count.
- Each section silently degrades to empty/zero — no errors. Use this on the home screen instead of fan-out calls.

### Notifications inbox endpoints
- **New: `GET /api/v1/notifications`** — paginated, newest first.
- **New: `GET /api/v1/notifications/unread-count`** — `{ "unread": N }` for the bell badge.
- **New: `POST /api/v1/notifications/{id}/read`** — mark single as read.
- **New: `POST /api/v1/notifications/mark-all-read`** — `{ "marked": N }`.
- `NotificationResponse` includes `payload` (the same JSON we passed at dispatch time) so cards can deep-link to the related receipt/product.

### Add missing items to a receipt before confirming
- **New: `POST /api/v1/receipts/{id}/items`** — for cases when SVRS missed a line. Only works on `PENDING_CONFIRMATION` receipts. Auto-assigns next position. Same body shape as the PATCH (minus the immutable rawDescription edits).

### `/actuator/health` is now public
- Spring Boot Actuator wired in; only `/actuator/health` is exposed publicly. Returns `200 {"status":"UP"}`. The keep-alive cron now hits this instead of `/legal/terms`. Use it for any uptime monitoring you wire externally.

### Password reset + email verification
- **New: `POST /api/v1/auth/forgot-password`** `{ email }` → 204. Always 204 even when email isn't registered (no enumeration leak).
- **New: `POST /api/v1/auth/reset-password`** `{ token, newPassword }` → 204. Token from the link, single-use, expires 60 min.
- **New: `POST /api/v1/auth/verify-email`** `{ token }` → 204. Token sent automatically on register, expires 24h.
- **New: `POST /api/v1/users/me/email-verification/resend`** — re-sends a fresh verification token if the old one expired.
- `User` now has `emailVerified` / `emailVerifiedAt` fields (visible via `GET /users/me` once you re-pull).
- **Dev shortcut:** until SMTP is wired in Render, the link is **logged with `[DEV-MODE]` prefix** instead of emailed. Grep server logs for the token. Documented in `DEV_NOTES.md`.

### Persistent shopping lists
- **New: `GET /api/v1/shopping-lists`** — household's lists (newest first).
- **New: `POST /api/v1/shopping-lists`** `{ name, items?: [{productId? | freeText?, quantity?}] }` — create.
- **New: `GET /api/v1/shopping-lists/{id}`** — detail with items.
- **New: `PATCH /api/v1/shopping-lists/{id}`** `{ name }` — rename.
- **New: `DELETE /api/v1/shopping-lists/{id}`** — delete (cascades items).
- **New: `POST /api/v1/shopping-lists/{id}/items`** — add item.
- **New: `POST /api/v1/shopping-lists/{id}/items/{itemId}/toggle`** — toggle checked.
- **New: `DELETE /api/v1/shopping-lists/{id}/items/{itemId}`** — remove item.
- Items can reference a canonical Product (auto-suggestions, optimizer-friendly) OR be free text (e.g. "papel higiênico" before we have a canonical Product for it).
- The existing stateless `POST /api/v1/shopping-list/optimize` (singular) stays for ad-hoc one-shot optimization.

---

## 2026-05-02

### Profile pictures
- **New: `POST /api/v1/users/me/profile-picture`** — multipart, field name `file`. JPG/PNG/WEBP, max 5 MB. Returns `{ "status": "ok" }`.
- **New: `GET /api/v1/users/me/profile-picture`** — returns raw image bytes (Content-Type matches the upload). 404 if not set.
- **New: `DELETE /api/v1/users/me/profile-picture`** — clears it.
- Storage is local-disk in dev (ephemeral on Render free tier — see `DEV_NOTES.md` for the prod plan). Contract won't change when we swap backends.

### `friendlyDescription` — rename items for display, with household memory
- **PATCH `/receipts/{id}/items/{itemId}`** now accepts `friendlyDescription` (max 500 chars). Set to override the noisy NFC-e text for display. `rawDescription` stays untouched (audit trail).
- **`PATCH …/items/{itemId}` no longer mutates `rawDescription`** even if you send it (kept in the request shape for backwards compat, marked deprecated in Swagger). To rename, use `friendlyDescription`.
- **`ReceiptItemResponse`** now has 3 description fields:
  - `rawDescription` — original NFC-e text, immutable
  - `friendlyDescription` — user override, null when not set
  - `displayDescription` — pre-resolved (`friendlyDescription` if set, else `rawDescription`). Use this for rendering.
- **Household memory**: when the user names an item linked to a Product, the name is remembered household-wide. Future receipts of the same Product (matched by EAN or alias) inherit `friendlyDescription` automatically — user types it once per product per household.

### Per-item exclusion on receipt confirm
- **POST `/receipts/{id}/confirm`** now accepts an optional body: `{ "excludedItemIds": ["uuid", ...] }`. Items in the list get marked excluded *before* downstream processing.
- **PATCH `/receipts/{id}/items/{itemId}`** accepts an `excluded: boolean` field too (toggle while reviewing).
- Excluded items stay on the receipt for audit but **don't count toward** spend, category insights, weekly insights, consumption predictions, price-history, or the collaborative price index.
- `ReceiptResponse` now exposes both `totalAmount` (original NF, immutable) and `householdTotalAmount` (sum of non-excluded items). Use `householdTotalAmount` for "what we actually spent".
- `ReceiptItemResponse.excluded` is the per-item flag.

### Per-household chave uniqueness + delete-receipt
- The `chave de acesso` was globally unique — only one user/household could ever import a given QR. Now **per-household**: two different households can both import the same fiscal event (couple split a bill, or QA testing).
- **New: `DELETE /api/v1/receipts/{id}`** — hard delete (any status, scoped to your household). Frees the chave for re-import.
- Error message updated: 409 now says "already in your household history" (was "already imported").

### Receipts list hides FAILED_PARSE rows
- When the SEFAZ HTML can't be parsed, we still persist the receipt with `status=FAILED_PARSE` + `rawHtml` for ops review. **Those rows are now hidden from `GET /receipts`** so they don't pollute the user's history list. The user still gets the 400 error on submit.

### `friendlyDescription` deprecates editable `rawDescription` on PATCH
- Already covered above — calling out separately because it's a small backwards-incompatible change. Sending `rawDescription` to PATCH is now a silent no-op (was: overwrote the field).

---

## 2026-05-02 (earlier — FE alignment batch)

### Insights enhancements
- **`GET /insights/spend`** response now includes `byWeek` array (besides existing `byMonth`, `byMarket`, `byCategory`).
- **`GET /insights/products/{id}/price-history`** points now carry `marketCnpj` per point (besides `marketName`) — needed to differentiate two stores of the same chain (e.g. Zaffari Hipica vs Zaffari Centro).
- All insights aggregations switched from `Receipt.totalAmount` to `SUM(item.totalPrice WHERE NOT excluded)` so they reflect what the household actually paid for, not the bill total.

### Households: kick member + invite expiration
- **New: `POST /api/v1/households/me/invite-code/regenerate`** — rotates the invite code, extends 48h TTL.
- **New: `DELETE /api/v1/households/me/members/{memberId}`** — kicks a member; they land in a fresh solo household.
- Invite codes now expire 48 h after generation (existing rows = NULL = never expires, backwards compat).
- `HouseholdResponse` exposes `inviteCodeExpiresAt`.

### Consumption (Phase 3) — snooze + manual purchase + upcoming + qty-aware
- Lowered min-purchases-for-prediction from 3 → 2.
- Quantity-aware ETA: if the last purchase was markedly larger than usual, the next-purchase ETA scales proportionally.
- **New: `POST /api/v1/consumption/snooze/{productId}`** with `{ "days": N }` — "Não preciso agora".
- **New: `DELETE /api/v1/consumption/snooze/{productId}`** — clear snooze.
- **New: `POST /api/v1/consumption/manual-purchase`** with `{ "productId", "quantity", "purchasedAt"? }` — "Já comprei sem nota". Counts toward intervals.
- **`GET /api/v1/consumption/suggested-list`** now accepts `?includeUpcoming=true&upcomingLimit=N` — empty state can show "Você está bem abastecido — próximos a vencer:".

### Shopping list optimizer
- **New: `POST /api/v1/shopping-list/optimize`** with `{ "items": [{productId, quantity}] }` → returns `{ marketPlans, estimatedTotal, unpriced }`. Greedy picks cheapest known market per item, groups by market.
- Each plan item carries `priceSource: LOCAL_HISTORY | COMMUNITY_INDEX`.
- Items with no price data land in `unpriced` (FE shows "preço indisponível" badge).

### Collaborative panel: city/state + k-anon hybrid disclosure
- Markets now record `city` + `state` (auto-filled from Nominatim geocoding).
- `PriceObservation` snapshots city/state at write time so retroactive geocode changes don't rewrite history.
- **`GET /price-index/.../reference`** now returns `kAnonBlocked: boolean`. When `true`, `medianPrice` is null but `sampleCount` and `distinctHouseholds` are still visible — FE can show the "poucas amostras" warning without us leaking sub-K-anon prices.

### Phase 2.6 — auto-derived household preferences
- **New: `GET /api/v1/preferences`** returns per-generic pack-size + brand preferences derived from the household's purchase history. Volume-gated (silent until 5+ purchases of a generic). Brand strength: `PREFERRED` (60–85% share) or `MUST_HAVE` (≥85%).

### Phase 5c — watched markets
- **New: `GET /api/v1/markets`** — picker catalogue (visited + watched + nearby).
- **New: `GET /api/v1/markets/watched`** — "Meus mercados".
- **New: `POST/DELETE /api/v1/markets/watched/{cnpj}`** — pin/unpin.
- Watched markets bypass the radius filter in `/price-index/best-markets` and `/price-index/promos`. Each row carries `watching: boolean`.

### Cross-cutting privacy + perf fixes
- All log lines now mask PII: emails (`a***@example.com`), chaves (`****6780`), push tokens (`****abcd`).
- Fixed an N+1 query in personal-promo detection.

---

## 2026-05-01 — earlier in the build-out

- **Phase 3 consumption intelligence (initial)** — predictions + suggested-list endpoints (later expanded above).
- **Phase 4 collaborative price index** — anonymized contributions, k-anon-protected reference price + best-markets + community promos endpoints.
- **Phase 5 geolocation + notifications** — `PATCH /users/me/location`, market geocoding via Nominatim, FCM push stub, SMTP email dispatcher, per-user notification preferences.
- **LGPD baseline** — opt-out flag, data export endpoint, account-deletion endpoint.

---

## How to use this file

- When you start a session, scroll from the top until you hit dates you've already read.
- Each entry is meant to be self-contained: what changed, what's new on the wire, what FE behavior should change.
- Major DTO/contract changes get explicit before/after notes.
- Bug fixes are listed only when the FE was likely tripping on the bug. Internal refactors aren't logged.
