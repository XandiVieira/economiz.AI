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

(Full infra + links: [INFRASTRUCTURE.md](./INFRASTRUCTURE.md).)

---

## 2026-06-07 — pharmacy detection now CNAE-verified (backend accuracy)

The pharmacy-merchant signal behind `PHARMACY` categorization is now **verified from the CNPJ's CNAE** (economic activity) via an external registry — `4771*` = pharmacy, `4711*/4712*` = supermarket — instead of only guessing from the merchant name. It runs async/best-effort (never blocks import; falls back to the name guess if the lookup fails), and when a merchant is confirmed a pharmacy, its previously-`OTHER` items are backfilled to `PHARMACY`. No API contract change — purely better category accuracy on items the FE already reads.

---

## 2026-06-07 — new `PHARMACY` category

Added a 9th global category **`PHARMACY`** (PT label "Farmácia") for drugstore/pharmacy items — vitamins, meds, supplements, first-aid. The category enum is now: `GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, PHARMACY, OTHER`. Add a `PHARMACY` chip/label to your category map.

Two-layer classification, item-first: (1) the **product dictionary** tags meds/supplements/dosage-forms as `PHARMACY` wherever bought (so a vitamin at a supermarket is caught too); (2) for items the dictionary can't place, a **pharmacy-merchant fallback** — if the receipt's merchant is a drugstore (detected from its name: Drogaria, Farmácia, Panvel, Droga Raia, Dimed, …), the unknown item defaults to `PHARMACY` instead of `OTHER`. Items the dictionary already knows keep their category (candy/cleaning bought at a drugstore stay correct). No contract change beyond the new enum value.

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
