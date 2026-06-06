# Frontend implementation report

Handoff notes for the **frontend** Claude — backend deltas that need FE work,
with concrete endpoints and UX guidance. Newest at the top. For the full API
contract see [API.md](./API.md); for the running diary see [CHANGELOG.md](./CHANGELOG.md).

- **API base:** `https://economizai.economizai.workers.dev/api/v1` (self-hosted)
- **Swagger:** `https://economizai.economizai.workers.dev/swagger-ui/index.html`
- Auth: `Authorization: Bearer <jwt>` on every call below. Send `Accept-Language: pt`.

---

## 2026-06-06 — Filter items by category (new screen)

**Goal:** user taps a category (e.g. "Carnes e Laticínios") → sees every item
they've bought in that category, across all receipts.

**Endpoint:** `GET /api/v1/items` — returns a paginated list of purchased line
items. All filters optional; combine freely.

```
GET /api/v1/items?category=MEAT_DAIRY&page=0&size=20
```

Other filters you can pass (same query for many screens — don't make new calls):
`from`, `to` (ISO datetime), `marketCnpj` (repeatable), `marketCnpjRoot`,
`category` (repeatable → OR), `productId` (repeatable), `ean` (repeatable),
`minReceiptTotal`, `maxReceiptTotal`, `page`, `size`.

**Response:** Spring page.
```json
{
  "content": [
    {
      "itemId": "uuid",
      "productId": "uuid|null",
      "category": "MEAT_DAIRY|null",
      "displayDescription": "Leite Italac 1L",   // use this for the row title
      "rawDescription": "LEITE ITALAC 1L",
      "friendlyDescription": "Leite Italac 1L|null",
      "ean": "7891234567890|null",
      "quantity": 1.000,
      "unit": "UN",
      "unitPrice": 5.49,
      "totalPrice": 5.49,
      "nfcePromoFlag": false,
      "receiptId": "uuid",        // deep-link to the receipt detail
      "marketName": "Zaffari",
      "marketCnpj": "93015006005182",
      "purchasedAt": "2026-05-15T10:00:00"
    }
  ],
  "totalElements": 42, "totalPages": 3, "number": 0, "size": 20
}
```

**Category chips** — the API uses these enum values; map to PT labels for display:

| enum (send this) | label (show this) |
|---|---|
| `GROCERIES` | Mercearia |
| `BEVERAGES` | Bebidas |
| `PRODUCE` | Hortifrúti |
| `MEAT_DAIRY` | Carnes e Laticínios |
| `BAKERY` | Padaria |
| `CLEANING` | Limpeza |
| `PERSONAL_CARE` | Higiene e Cuidado Pessoal |
| `OTHER` | Outros |

**UX / behavior notes:**
- Scope is **confirmed purchases only** (excluded items are dropped server-side) — no client filtering needed.
- Default sort is newest first (`purchasedAt` desc). No sort param yet — ask backend if you need one.
- Empty result is an **empty page** (`content: []`, `totalElements: 0`), not a 404 — render an empty state.
- Row title: prefer `displayDescription`. Show `marketName` + `purchasedAt` as subtitle, `totalPrice`/`unitPrice` on the right. `nfcePromoFlag: true` → "promoção" badge. `category: null` means the item isn't categorized yet (uncategorized products); it won't appear under any category filter.
- **Drill-down pattern:** the existing `/insights/query` returns aggregate buckets with a `key` (a productId, category, or CNPJ). Tap a bucket → call `/items` with that key as a filter to list the underlying items. Same data, two granularities — reuse one list component.

**Pagination:** standard Spring — `?page=&size=`, read `totalElements`/`totalPages`/`number`.

---

## 2026-06-06 — Price alerts ("avise-me quando") (new screen, if not done yet)

Backend shipped; FE screen may still be pending. Lets a user be notified when a
product drops below a price.

```
POST   /api/v1/alerts      { "productId": "<uuid>", "thresholdPrice": 5.99, "radiusKm"?: 5, "active"?: true }  → 201
GET    /api/v1/alerts      → list of the user's alerts
DELETE /api/v1/alerts/{id} → 204
```

- One rule per (user, product) — re-`POST`ing the same product **updates** it (safe to use as upsert).
- `radiusKm` optional (from the user's home); omit for "anywhere".
- When a rule fires it creates a notification of type **`PRICE_DROP`** — it shows up in the existing notifications inbox (`GET /notifications`) and is pushed if a push token is registered. No separate fetch needed beyond the inbox you already have.
- Full contract: API.md §10e.

---

## Notes
- Swagger is auto-generated from the live build — if an endpoint isn't in
  `/v3/api-docs`, the server is running an older image (rebuild on the host).
- No data-model/breaking changes in the above — these are additive read/CRUD endpoints.
