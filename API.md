# economizai — API guide for the FE

Practical walk-through of the backend, organized by user flow. For the
machine-readable contract, hit **`/swagger-ui`** on whichever environment
you're against (everything's annotated with descriptions and examples).

- **Production:** `https://economiz-ai.onrender.com`
- **Local:** `http://localhost:8080`

All `/api/v1/**` routes (except `/auth/*` and `/legal/*`) require a JWT in the
`Authorization: Bearer <token>` header. Tokens expire after 24h —
re-login when you get a 401. The header `Accept-Language: pt` gives Portuguese
error messages; default is `pt`, fall back to `en` is supported.

> **Naming convention.** "Receipt" / "nota" / "NFC-e" all refer to the same
> thing. The literal Portuguese terms (chave de acesso, CNPJ, NFC-e, SEFAZ) are
> kept untranslated in payloads — they're legal identifiers.

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
→ 201 { "token": "...", "user": { ... } }
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
→ 200 { "token": "...", "user": { ... } }
```

JWT logout is FE-side: drop the token from storage. There is no `/logout`
endpoint by design.

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
```

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
| 409 `receipt.already.ingested` | This chave was already submitted by some user |

### Review + confirm

After submit, the user sees `status=PENDING_CONFIRMATION` with parsed items.
They can:

```
GET    /api/v1/receipts/{id}                         → full receipt with items
PATCH  /api/v1/receipts/{id}/items/{itemId}          → fix typos / qty
POST   /api/v1/receipts/{id}/confirm                 → commit. Returns { receipt, personalPromos: [...] }
POST   /api/v1/receipts/{id}/reject                  → discard. Receipt stays as REJECTED in history.
```

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
    &cnpjEmitente=83261420003255
    &category=GROCERIES
    &page=0&size=20
→ Page<ReceiptSummaryResponse>  (each row has marketName, issuedAt, totalAmount, itemCount, status)
```

Default sort is `issuedAt DESC`. All filters optional.

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
GET /api/v1/preferences
→ list of HouseholdPreferenceResponse (one per generic the household buys regularly)
```

Auto-derived from purchase history — no manual override yet. Surfaces the
typical pack size + dominant brand per generic. **Volume gate**: silent until
the household has 5+ confirmed purchases of a given generic.

`brandStrength`: `PREFERRED` (top brand 60–85% share) or `MUST_HAVE` (>85%). FE
can render as a soft hint ("você costuma comprar Italac") or a hard filter on
suggested-list views.

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

---

## Postman collection

Full request library + sequential E2E flow at
`postman/economizai.postman_collection.json`. Set the `baseUrl` collection
variable to your environment, optionally set `qrPayload` to a real NFC-e for
the receipt steps.
