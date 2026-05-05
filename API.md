# economizai — API guide for the FE

Practical walk-through of the backend, organized by user flow. For the
machine-readable contract, hit **`/swagger-ui`** on whichever environment
you're against (everything's annotated with descriptions and examples).

- **Production:** `https://economiz-ai.onrender.com`
- **Local:** `http://localhost:8080`

All `/api/v1/**` routes (except `/auth/*` and `/legal/*`) require a JWT in the
`Authorization: Bearer <token>` header. Access tokens expire after 24h —
exchange the long-lived refresh token (30d) for a new pair via
`POST /auth/refresh` instead of forcing the user back to the login screen. The
header `Accept-Language: pt` gives Portuguese error messages; default is `pt`,
fall back to `en` is supported.

> **Naming convention.** "Receipt" / "nota" / "NFC-e" all refer to the same
> thing. The literal Portuguese terms (chave de acesso, CNPJ, NFC-e, SEFAZ) are
> kept untranslated in payloads — they're legal identifiers.

---

## 0. App-open snapshot (use this on the home screen)

```
GET /api/v1/dashboard
```

Single round-trip returns:
- `currentMonth` — `{ year, month, total, receiptCount, averageTicket }`
- `recentReceipts` — last 5 confirmed receipts (newest first)
- `suggestedShoppingList` — top 5 RAN_OUT/RUNNING_LOW items
- `communityPromosNearby` — top 5 community promos (radius-aware, watched markets bypass)
- `unreadNotificationCount` — bell-badge value
- `generatedAt` — server timestamp

Each section silently degrades to empty/zero when there's nothing to show. Use this instead of fan-out calls on cold start (saves you ~5×30s on Render free tier).

```
GET /actuator/health   → public, returns `{"status":"UP"}` — for uptime monitors
```

---

## 1. Auth + onboarding

### Register

```
POST /api/v1/auth/register
{
  "name": "Maria Silva",
  "email": "maria@example.com",
  "password": "atLeast8chars",
  "acceptedTermsVersion": "1.0",
  "acceptedPrivacyVersion": "1.0"
}
→ 201 { "token": "...", "refreshToken": "...", "user": { ... } }
```

The terms/privacy versions come from `GET /api/v1/legal/terms` and
`GET /api/v1/legal/privacy-policy`. Show the docs to the user, then send the
version they actually saw. Stale versions are rejected with 400.

A **household is auto-created** at register time. The new user is its only
member, and they get an `inviteCode` valid for 48h. They can:
- Stay in their solo household (single-person tracking).
- Share the code with a partner who joins (couple/family tracking).

### Login

```
POST /api/v1/auth/login
{ "email": "maria@example.com", "password": "..." }
→ 200 { "token": "...", "refreshToken": "...", "user": { ... } }
```

### Refresh + logout

```
POST /api/v1/auth/refresh    { "refreshToken": "..." }
                              → 200 { "token": "...", "refreshToken": "...", "user": {...} }

POST /api/v1/auth/logout     { "refreshToken": "..." }   → 204 (idempotent)
```

The refresh token is **single-use** — every `/refresh` call returns a new pair, and the previous refresh token is marked consumed. If a consumed token is replayed, you get `400 auth.token.invalid` (a sign that someone may be replaying tokens). Refresh tokens live for 30 days; access tokens for 24h. Call `/refresh` proactively when an access token is close to expiring, or reactively on the first 401 you get.

`/logout` revokes the presented refresh token. Always returns 204, even when the token is unknown — keeps the endpoint idempotent. The access token is still technically valid until it expires (24h max), so the FE should also drop both tokens from storage.

### Profile

```
GET    /api/v1/users/me                 → current user
PUT    /api/v1/users/me                 { "name": "..." }
PUT    /api/v1/users/me/password        { "currentPassword": "...", "newPassword": "..." }
DELETE /api/v1/users/me                 → LGPD account deletion (cascades all data)
GET    /api/v1/users/me/export          → LGPD data export (user + household + receipts)
PATCH  /api/v1/users/me/contribution    { "contributionOptIn": false }   ← LGPD opt-out from collaborative panel
PATCH  /api/v1/users/me/location        { "latitude": -30.0277, "longitude": -51.2287 }
PATCH  /api/v1/users/me/push-token      { "pushDeviceToken": "<FCM>" }   ← null/empty to clear
GET    /api/v1/users/me/notification-preferences
PUT    /api/v1/users/me/notification-preferences
       { "preferences": [ { "type": "PROMO_PERSONAL", "channel": "PUSH" }, ... ] }

POST   /api/v1/users/me/profile-picture   ← multipart form, field name "file"
                                            JPG/PNG/WEBP, max 5MB
GET    /api/v1/users/me/profile-picture   ← returns the bytes (Content-Type matches the upload)
DELETE /api/v1/users/me/profile-picture
```

**Profile picture**: standard multipart upload. The response is JSON `{ "status": "ok" }` on success.

- **On upload**: server-side downscales JPEG/PNG to a 512px max dimension before storing (saves disk + bandwidth, no FE work).
- **On GET**: returns the raw image bytes. **Never 404s** — when no picture has been uploaded, the server generates a deterministic initials avatar (PNG, 256x256, color hashed from email) so `<img>` tags always render. Inspect the **`X-Profile-Picture-Fallback: true|false`** response header to distinguish a generated avatar from a user-uploaded photo (handy for "edit photo" vs "upload photo" copy).
- **WebP**: stored as-is (no resize). All other formats (JPEG/PNG) are normalized.
- Storage is local-disk in dev (ephemeral on Render free tier — see `DEV_NOTES.md` for the prod plan); the API contract won't change when we swap backends.

### Password reset + email verification

```
POST /api/v1/auth/forgot-password    { "email": "..." }                       → 204
POST /api/v1/auth/reset-password     { "token": "...", "newPassword": "..." } → 204
POST /api/v1/auth/verify-email       { "token": "..." }                       → 204
POST /api/v1/users/me/email-verification/resend                               → 204
```

`forgot-password` always returns 204 — even when the email isn't registered, to avoid leaking valid addresses. `reset-password` and `verify-email` return 400 on stale/used tokens.

**Dev mode**: when SMTP isn't configured (current Render setup), the link is logged with a `[DEV-MODE]` prefix in the server logs instead of being emailed. The endpoints still return 204, so the flow works for FE testing — grab the token from logs.

`UserResponse` now includes `emailVerified` + `emailVerifiedAt`. You can gate features behind `emailVerified === true` if you want.

---

## 2. Households

Every user already has a household (auto-created at register). To merge two
users into one shared history:

```
POST /api/v1/households/join          { "inviteCode": "ABC123" }   ← case-insensitive
POST /api/v1/households/leave         → moves caller into a fresh solo household
GET  /api/v1/households/me            → current household + members + invite code + expiration
POST /api/v1/households/me/invite-code/regenerate   → rotates the code, extends TTL another 48h
DELETE /api/v1/households/me/members/{memberId}     → kicks a member; they get a fresh solo household
```

**Invite codes expire after 48h.** When that happens, `/join` returns 400 with
the same message as an unknown code (we don't tell the requester whether the
code was wrong vs. stale — both look the same to a malicious user). The owner
regenerates and shares the new one.

When the last member leaves a household, the household row is deleted
automatically.

---

## 3. Receipts (the core flow)

### Submit a scanned NFC-e

```
POST /api/v1/receipts
{
  "qrPayload": "<whatever the camera scanned>"
}
→ 201 ReceiptResponse with status="PENDING_CONFIRMATION"
```

**`qrPayload` accepts four shapes** — they're all parsed into the 44-digit chave
de acesso server-side. Pass exactly what the QR scanner returned:

1. SVRS landing URL: `https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=<chave>|3|1`
2. Direct portal URL: `https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p=<chave>|3|1`
3. Bare pipe payload: `<44-digit-chave>|3|1`
4. Bare 44-digit chave

### Error paths

| Response | When |
|---|---|
| 400 `receipt.qr.invalid` | Couldn't extract a 44-digit chave from the input |
| 400 `receipt.state.unsupported` | Chave is from a state we don't have a SEFAZ adapter for (only RS today) |
| 502 `receipt.sefaz.fetch.failed` | SVRS portal didn't respond / 5xx'd |
| 400 `receipt.parse.failed` | We fetched HTML but the parser couldn't extract items. **The receipt is still saved with `status=FAILED_PARSE` + `rawHtml`** so ops can patch the parser without you re-scanning. |
| 409 `receipt.already.ingested` | This chave is already in **your household's** history. Other households can still import the same chave; this only blocks double-import within yours. Delete it via `DELETE /receipts/{id}` to free the slot. |

### Review + confirm

After submit, the user sees `status=PENDING_CONFIRMATION` with parsed items.
They can:

```
GET    /api/v1/receipts/{id}                         → full receipt with items
PATCH  /api/v1/receipts/{id}/items/{itemId}          → fix typos / qty / toggle excluded / set friendlyDescription
POST   /api/v1/receipts/{id}/items                   → add a missing item (PENDING_CONFIRMATION only)
POST   /api/v1/receipts/{id}/confirm                 → commit. Optional body { excludedItemIds: [uuid, ...] }
                                                        Returns { receipt, personalPromos: [...] }
POST   /api/v1/receipts/{id}/reject                  → discard. Receipt stays as REJECTED in history.
DELETE /api/v1/receipts/{id}                         → hard delete. Frees the chave so it can be re-imported.
```

**Per-item display name (`friendlyDescription`)** — NFC-e descriptions are noisy ("ARROZ TIO J TP1 5KG"). The user can rename an item for display via `PATCH /receipts/{id}/items/{itemId}` with `{ "friendlyDescription": "Arroz Tio João 5kg" }`. The original `rawDescription` stays untouched (it's the legal audit text from SEFAZ — immutable).

The response always includes both:
- `rawDescription` — original NFC-e text, never changes
- `friendlyDescription` — user override, null when not set
- `displayDescription` — derived: `friendlyDescription` if set, else `rawDescription`. Use this for rendering.

**Household memory** — when the user names an item that's linked to a Product, the name is remembered household-wide. Future receipts that contain the same Product (matched by EAN or alias) will inherit `friendlyDescription` automatically — the user only types it once. Different households can have different names for the same product.

**Per-item exclusion** — when the household shopped together with someone outside (a friend, a roommate's purchase) and only some items are theirs, the user can mark items as excluded. Excluded items:

- Stay on the receipt for audit (the original NF is a legal document, we don't rewrite it)
- Don't count toward `householdTotalAmount`
- Don't contribute to the collaborative price index
- Don't feed consumption-cadence predictions
- Don't appear in spend totals or category insights

Two ways to mark an item excluded:

1. **At confirm time**: `POST /receipts/{id}/confirm` with `{ "excludedItemIds": ["uuid-1", "uuid-2"] }` in the body. Items in the list get marked excluded *before* downstream processing.
2. **Per-item PATCH** (works on PENDING_CONFIRMATION receipts): `PATCH /receipts/{id}/items/{itemId}` with `{ "excluded": true, ... }`.

`ReceiptResponse` now exposes both:
- `totalAmount` — the original NF total. Never changes.
- `householdTotalAmount` — sum of non-excluded items. Use this for "what we actually spent".

**Item-level promo flag (new)** — each `ReceiptResponse.items[*]` now carries `nfcePromoFlag: boolean`. True when the SEFAZ HTML signaled the line was on promo / discount (a discount cell was present, or the description carries stems like "OFERTA", "PROMO", "DESCONTO", "COMBO", "LEVE 3"). Use it for visual emphasis ("oferta!" badge) on receipt detail cards. Backend behavior: flagged items are excluded from baseline calcs in community-promo detection so we don't compare promos to historic promos.

**Item-level category (new)** — each `ReceiptResponse.items[*]` now carries `category: string | null`. Resolved from the linked `Product` (when the item has been canonicalized). Values: `GROCERIES`, `BEVERAGES`, `PRODUCE`, `MEAT_DAIRY`, `BAKERY`, `CLEANING`, `PERSONAL_CARE`, `OTHER`. `null` when the item hasn't been linked to a Product yet, or the Product has no category set. Use it to show a category chip per line on the ReviewScreen / receipt detail without a follow-up `GET /products/{id}` call.

Confirm is what triggers downstream side effects:
- Item canonicalization (raw text → canonical Product)
- Anonymized contribution to the collaborative price index (skipped if `contributionOptIn=false`)
- Personal promo detection (returned in the response)
- Geocoding of new markets (async, doesn't block)

`personalPromos` in the confirm response is the list of items the user paid
notably less for than usual — surface as "Você economizou" cards.

### List + filter

```
GET /api/v1/receipts
    ?from=2026-04-01T00:00:00
    &to=2026-04-30T23:59:59
    &marketCnpj=83261420003255
    &category=GROCERIES
    &q=leite condensado
    &page=0&size=20
→ Page<ReceiptSummaryResponse>  (each row has marketName, issuedAt, totalAmount, itemCount, status)
```

Default sort is `issuedAt DESC`. All filters optional.

**Content search (`q`)** — case-insensitive substring match against `rawDescription`, `friendlyDescription`, the linked product's normalized name, AND the receipt's market name. So `q=leite` finds every receipt that includes a milk item OR was issued by a market with "leite" in the name. Combine with `from`/`to`/`category` freely.

---

## 4. Insights / dashboards

```
GET /api/v1/insights/spend?from=&to=
```
Returns:
```json
{
  "from": "2026-04-01T00:00:00",
  "to": "2026-04-30T23:59:59",
  "total": 1234.50,
  "byMonth": [{ "year": 2026, "month": 4, "total": 1234.50, "receiptCount": 5 }],
  "byWeek":  [{ "year": 2026, "week": 17, "total": 312.00, "receiptCount": 1 }, ...],
  "byMarket": [{ "cnpj": "...", "marketName": "...", "total": 850.00, "receiptCount": 3 }, ...],
  "byCategory": [{ "category": "GROCERIES", "total": 600.00, "itemCount": 18 }, ...]
}
```
- **Ticket médio** is `total / receipts.count` — compute on the FE.
- **Empty state** = `total: 0` + all arrays empty. No errors.

```
GET /api/v1/insights/markets/top?limit=5
GET /api/v1/insights/categories/top?limit=5
GET /api/v1/insights/products/{productId}/price-history?from=&to=
```

`price-history` returns chronological points, **each tagged with marketCnpj +
marketName** so the FE can color the points to differentiate stores of the same
chain (Zaffari Hipica vs Zaffari Centro both render as "ZAFFARI" but have
different CNPJs).

### Flexible spend slicer (use this for any cross-filtered chart)

When the four endpoints above don't compose the slice you need (e.g., "spend on GROCERIES at Zaffari last month, by week"), reach for the unified query endpoint instead:

```
GET /api/v1/insights/query
    ?from=2026-04-01T00:00:00
    &to=2026-04-30T23:59:59
    &marketCnpj=93015006005182        ← repeat for OR: ?marketCnpj=A&marketCnpj=B
    &marketCnpjRoot=93015006          ← chain-level (8-digit CNPJ root), list-typed
    &category=GROCERIES               ← repeat for OR: ?category=GROCERIES&category=BEVERAGES
    &productId=<uuid>                 ← list-typed
    &ean=7891234567890                ← list-typed
    &minReceiptTotal=100.00           ← receipt-total range
    &maxReceiptTotal=500.00
    &groupBy=WEEK                     ← see list below
    &limit=100                        ← bucket cap (default 100, max 500)
```

**`groupBy`** is a single dimension (one at a time):
- **Temporal:** `DAY`, `WEEK`, `MONTH`, `YEAR` — sorted ascending
- **Categorical:** `MARKET` (full CNPJ), `CHAIN` (CNPJ root), `CATEGORY`, `PRODUCT` — sorted descending by total
- **`NONE`** (default) — return only the summary, no buckets

**Response shape:**
```json
{
  "filters": {
    "from": "2026-04-01T00:00:00",
    "to": "2026-04-30T23:59:59",
    "marketCnpjs": ["93015006005182"],
    "marketCnpjRoots": null,
    "categories": ["GROCERIES"],
    "productIds": null,
    "eans": null,
    "minReceiptTotal": null,
    "maxReceiptTotal": null
  },
  "summary": {
    "total": 234.50,
    "receiptCount": 3,
    "itemCount": 18,
    "averageTicket": 78.17
  },
  "groupBy": "WEEK",
  "buckets": [
    { "key": "2026-W14", "label": "2026-W14", "total": 80.00, "receiptCount": 1, "itemCount": 6, "averageTicket": 80.00 },
    { "key": "2026-W15", "label": "2026-W15", "total": 154.50, "receiptCount": 2, "itemCount": 12, "averageTicket": 77.25 }
  ]
}
```

**Conventions:**
- All filters are **optional** — empty filter = no constraint on that dimension.
- Repeated query params = OR within the same dimension (`?category=A&category=B`). Different dimensions are AND'd together.
- `key` is the canonical machine value (CNPJ, UUID, "GROCERIES", "2026-04"). `label` is the human-friendly version (market name, product name) — use it for display.
- Empty result is `summary.total = 0` + `buckets = []`, NOT a 404.

**Examples for common dashboards:**
| Question | Query |
|----------|-------|
| Weekly spend in April | `?from=2026-04-01T00:00:00&to=2026-04-30T23:59:59&groupBy=WEEK` |
| Top 5 markets this year | `?from=2026-01-01T00:00:00&groupBy=MARKET&limit=5` |
| Spend per chain (collapse store-level CNPJs) | `?groupBy=CHAIN` |
| Category breakdown at one specific market | `?marketCnpj=93015006005182&groupBy=CATEGORY` |
| Top 20 products in groceries OR beverages | `?category=GROCERIES&category=BEVERAGES&groupBy=PRODUCT&limit=20` |
| Big shopping trips only (R$200+) by month | `?minReceiptTotal=200.00&groupBy=MONTH` |
| Total spend on milk EAN | `?ean=7891234567890` (no groupBy = just summary) |

The legacy `/insights/spend`, `/markets/top`, `/categories/top`, `/products/{id}/price-history` endpoints stay around for the existing dashboards. Use `/insights/query` for anything custom.

---

## 5. Products

```
GET   /api/v1/products?query=arroz&page=0&size=20    → search by name or exact EAN
GET   /api/v1/products/{id}                          → single product
POST  /api/v1/products                               → create canonical product (rare; usually auto-created on confirm)
PATCH /api/v1/products/{id}                          → set category/brand/etc
POST  /api/v1/products/{id}/aliases                  { "rawDescription": "<raw NFC-e text>" }
GET   /api/v1/products/unmatched                     → review queue: receipt items the system couldn't match
```

The "review queue" is the workflow for messy receipts: items that didn't auto-
match show up here, the user picks the right product, and the alias is
automatically backfilled to all matching items.

---

## 6. Markets + watchlist

```
GET    /api/v1/markets[?radiusKm=10]                 → catalogue (visited + watched + nearby)
GET    /api/v1/markets/watched                       → "Meus mercados"
POST   /api/v1/markets/watched/{cnpj}                → pin
DELETE /api/v1/markets/watched/{cnpj}                → unpin
```

Each row carries `visited` (household has shopped here) and `watching` (user
pinned it) flags. Watched markets bypass the home-radius filter in price
intelligence — useful for "the market on my commute is far from home but I want
its promos anyway".

---

## 7. Collaborative price index

K-anonymity protected — queries return empty (or `kAnonBlocked: true`) until
≥ 3 distinct households contributed.

```
GET /api/v1/price-index/products/{productId}/markets/{cnpj}/reference
→ {
    "medianPrice": 28.50,
    "minPrice":    24.00,
    "maxPrice":    32.00,
    "sampleCount": 12,
    "distinctHouseholds": 4,
    "mostRecentAt": "2026-04-28T10:00:00",
    "kAnonBlocked": false
  }
```

When `kAnonBlocked: true`, `medianPrice` is `null` but `sampleCount` /
`distinctHouseholds` are still visible — show the "poucas amostras" warning.

```
GET /api/v1/price-index/products/{productId}/best-markets?limit=10&radiusKm=5
GET /api/v1/price-index/promos?radiusKm=5
```

`promos` returns currently-detected community promos in the user's area
(recent median ≥ 15% below baseline, k-anon protected).

---

## 8. Consumption intelligence

Per-product purchase prediction + suggested shopping list, derived from
confirmed receipts + manual purchases.

```
GET /api/v1/consumption/predictions
→ list of ConsumptionPredictionResponse, sorted by daysUntilNextPurchase ASC
```

Each prediction has `status` (`OK` / `RUNNING_LOW` / `RAN_OUT`),
`daysUntilNextPurchase` (negative when overdue), `confidence` (LOW/MEDIUM/HIGH),
`averageQuantityPerPurchase`. **Volume gate**: products with fewer than 2 prior
purchases are silently skipped. Returns empty array, never an error.

```
GET /api/v1/consumption/suggested-list[?includeUpcoming=true&upcomingLimit=5]
→ { "items": [ ... ], "generatedAt": "..." }
```

By default, only `RAN_OUT` + `RUNNING_LOW` items. With `includeUpcoming=true`,
also includes the next N `OK`-status items so the empty state can read "Você
está bem abastecido — próximos a vencer:" instead of being literally empty.

```
POST   /api/v1/consumption/snooze/{productId}        { "days": 7 }   ← "Não preciso agora"
DELETE /api/v1/consumption/snooze/{productId}        → clear snooze
POST   /api/v1/consumption/manual-purchase           { "productId": "...", "quantity": 1 }   ← "Já comprei sem nota"
```

Snoozes and manual purchases are per-household. A manual purchase implicitly
clears any active snooze on that product (the user just took action).

---

## 9. Shopping list optimizer (PRO-52)

```
POST /api/v1/shopping-list/optimize
{
  "items": [
    { "productId": "<uuid-1>", "quantity": 2 },
    { "productId": "<uuid-2>", "quantity": 1 }
  ]
}
→ {
    "marketPlans": [
      {
        "marketCnpj": "...",
        "marketName": "...",
        "subtotal": 35.40,
        "itemCount": 2,
        "items": [ { "productId": "...", "quantity": 2, "estimatedUnitPrice": 12.50, "estimatedSubtotal": 25.00, "priceSource": "LOCAL_HISTORY" } ]
      }
    ],
    "estimatedTotal": 35.40,
    "unpriced": [ { "productId": "...", "reason": "no observed price (local or community)" } ]
  }
```

Greedy heuristic: per item, pick the cheapest known market. Price source
priority: (1) household's own most-recent purchase, (2) community-index
median, (3) unpriced (FE shows "preço indisponível" badge).

No travel-time modeling yet — V1 picks lowest-cost regardless of how many
markets the user would have to visit.

---

## 10. Household preferences (Phase 2.6)

```
GET    /api/v1/preferences
       → list of HouseholdPreferenceResponse (one per generic the household buys regularly)

PUT    /api/v1/preferences/brand/{genericName}
       { "brand": "Itambé", "strength": "MUST_HAVE" }    → 204
DELETE /api/v1/preferences/brand/{genericName}            → 204
```

Auto-derived from purchase history. Surfaces the typical pack size + dominant brand per generic. **Volume gate** for derived entries: silent until the household has 5+ confirmed purchases of a given generic.

`brandStrength`: `PREFERRED` (top brand 60–85% share) or `MUST_HAVE` (>85%). FE can render as a soft hint ("você costuma comprar Italac") or a hard filter on suggested-list views.

**Manual brand override** — `PUT /preferences/brand/{genericName}` lets the user explicitly say "for milk, my brand is Itambé". The override **wins over derived** in `GET /preferences`: the row will carry the user's chosen brand + strength, but the underlying `brandDistribution`, `sampleSize`, and pack-size fields stay derived so the user still sees the historical signal. Manual entries can also surface generics the household hasn't bought yet (sample size will be 0). `DELETE` to clear and fall back to derived.

---

## 10b. Notifications inbox

```
GET  /api/v1/notifications?page=0&size=20    → paginated list, newest first
GET  /api/v1/notifications/unread-count      → { "unread": N }
POST /api/v1/notifications/{id}/read         → mark single as read
POST /api/v1/notifications/mark-all-read     → { "marked": N }
```

Each `NotificationResponse` carries `payload` — the JSON we attached when we generated the notification (`receiptId`, `productId`, `savingsPct`, etc) so cards can deep-link.

---

## 10c. Persistent shopping lists

For one-shot ad-hoc optimization, see §9. For build-edit-shop workflows, use these:

```
GET    /api/v1/shopping-lists                                          → list (newest first)
POST   /api/v1/shopping-lists                                          → create
       { "name": "...", "items"?: [ { "productId"? | "freeText"?, "quantity"? } ] }
GET    /api/v1/shopping-lists/{id}                                     → detail with items
PATCH  /api/v1/shopping-lists/{id}                                     → rename
       { "name": "..." }
DELETE /api/v1/shopping-lists/{id}                                     → delete (cascades items)

POST   /api/v1/shopping-lists/{id}/items                               → add item
       { "productId"? | "freeText"?, "quantity"? }
POST   /api/v1/shopping-lists/{id}/items/{itemId}/toggle               → toggle checked
DELETE /api/v1/shopping-lists/{id}/items/{itemId}                      → remove item
```

Each item is **either** linked to a canonical `Product` (auto-suggestion-friendly) **or** free text — the request must include exactly one. Free-text entries can be upgraded later by replacing the row with a productId-bound one.

`ShoppingListResponse.items[*].displayName` is the resolved label — `productName` if linked, else `freeText`.

---

## 10d. Admin (ROLE_ADMIN only — not consumed by the FE)

```
GET    /api/v1/admin/users?q=&page=&size=   → Page<AdminUserSummaryResponse>
GET    /api/v1/admin/users/{id}              → AdminUserDetailResponse
GET    /api/v1/admin/receipts?from=&to=&marketCnpj=&category=&q=&householdId=&page=&size=
                                              → Page<ReceiptSummaryResponse>
GET    /api/v1/admin/receipts/{id}            → ReceiptResponse
POST   /api/v1/admin/receipts/{id}/reparse    → 200 ReceiptResponse
POST   /api/v1/admin/notifications/test       → 202 Accepted
```

- **Users list** — `q` does substring match on email + name (case-insensitive). Sorted by `createdAt` desc by default.
- **User detail** — bundles `householdId`, `householdMemberCount`, `receipts` counts (PENDING_CONFIRMATION / CONFIRMED / REJECTED / FAILED_PARSE), and `spendLast30Days`. Useful for triaging "why is X seeing Y".
- **Receipts list** — same content-search semantics as `GET /receipts` (substring on raw + friendly description, product name, market name) but cross-household. `householdId` is an additional optional filter to scope to one household. Includes `FAILED_PARSE` rows (useful for parser triage).
- **Receipts get** — bypasses the per-household ownership check.
- **Reparse** — re-runs parsing on the receipt's stored raw HTML and replaces its items with the freshly-parsed ones. Resets `status` to `PENDING_CONFIRMATION` and clears `confirmedAt` — the owner re-confirms to commit. Useful when a parser fix lands. 400 if `rawHtml` is missing (e.g. legacy rows from before we persisted it).
- **Notifications test** — body `{ "email": "...", "title"?, "body"?, "type"? }`. Resolves the user by email and dispatches a payload through `NotificationService`. Useful to verify push/SMTP wiring on demand. Returns 202 even if the channel is stubbed; check the inbox (`GET /notifications`) or the device to confirm delivery. `type` defaults to `SYSTEM`.

All require a JWT for a user with `Role.ADMIN`. Regular users hit 403.

---

## 11. Categorizer admin (rarely needed by the FE)

```
GET  /api/v1/categorizer/status        → ML model state
POST /api/v1/categorizer/retrain       → trigger retraining manually
POST /api/v1/categorizer/auto-promote  → trigger learned-dictionary promotion
```

Mostly for ops. Categorization runs automatically on receipt confirm.

---

## 12. Legal

```
GET /api/v1/legal/terms             → { "version": "1.0", "content": "...", "updatedAt": "..." }
GET /api/v1/legal/privacy-policy    → same shape
```

Both are public (no auth). Show before register; pass the returned `version` in
the register request.

---

## Common patterns + gotchas

- **All money** is `BigDecimal` with 2 decimals (R$ centavos). Never parse as
  float on the FE — keep as string or use a money lib.
- **All quantities** are `BigDecimal` with 3 decimals (kilos, liters, units).
- **All timestamps** are ISO-8601 without timezone (`2026-04-28T10:00:00`).
  Server timezone is America/Sao_Paulo.
- **CNPJ** strings are always 14 digits, no formatting (`93015006005182`, not
  `93.015.006/0005-182`).
- **Empty lists are not errors.** A new user with no receipts gets `total: 0`
  and empty arrays from `/insights/spend`, not 404. Render the empty state.
- **The k-anon hybrid** lets you show "poucas amostras" warnings without
  leaking sub-K-anon data. Check `kAnonBlocked` on `ReferencePrice`.
- **Volume gates** mean some endpoints return empty until enough data
  accumulates (predictions: ≥2 per product, preferences: ≥5 per generic,
  reference price: ≥5 samples + ≥3 households). This is by design.
- **Rate limiting.** `POST /auth/*` is capped at **5 req/min/IP**;
  `POST /receipts` is capped at **30 req/hour/user**. Over-quota responses
  are `429 Too Many Requests` with a `Retry-After: <seconds>` header and a
  translated message body. Successful responses on rate-limited routes
  carry `X-RateLimit-Remaining` so the FE can warn the user before they
  hit the wall. Other routes are uncapped.

---

## Postman collection

Full request library + sequential E2E flow at
`postman/economizai.postman_collection.json`. Set the `baseUrl` collection
variable to your environment, optionally set `qrPayload` to a real NFC-e for
the receipt steps.
