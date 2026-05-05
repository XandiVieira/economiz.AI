# economizai — what's new

FE-facing diary of meaningful backend changes — new endpoints, response-shape
changes, behavior changes, gotchas worth knowing about. **Newest at the top.**
Skim from the top until you hit a date you've already read.

For the complete API contract see [API.md](./API.md) (walk-through) or
`/swagger-ui` on whichever environment you're hitting. Production:
`https://economiz-ai.onrender.com`.

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
