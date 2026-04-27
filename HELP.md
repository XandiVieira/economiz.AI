# economizai — Development Log

## Project Vision

economizai (GitHub: **economiz.AI**) is a collaborative price-intelligence app
built around Brazilian **NFC-e** (Nota Fiscal de Consumidor Eletrônica). Users
scan the QR code on their grocery receipts, the system parses every line item,
and that history powers two complementary products:

1. **Personal money tracker** — every product the user has ever bought, where,
   when, for how much. Spend dashboards, price-evolution charts, suggested
   shopping lists, stock-out predictions, and "where should I buy this next?"
   recommendations.
2. **Collaborative price index** — every uploaded receipt also contributes
   anonymized price/market/date data to a shared base. All users benefit from
   reference prices, deal detection, and "cheapest market right now" insights
   that nobody buying alone could ever generate.

**Core value proposition:** "Scan your receipts. Stop overpaying. The whole
community wins."

### Key Differentiators

- **Zero data-entry friction.** NFC-e is a Brazilian legal artifact — every
  retail purchase already has a structured receipt. We ride that rail instead
  of asking users to type prices.
- **Network effect by construction.** Every user who joins to track their own
  spending automatically improves the price index for everyone else.
- **Brazil-native.** Per-state SEFAZ adapters, CNPJ-keyed market identity, R$
  pricing, Portuguese-first UI. International expansion is explicitly out of
  scope for V1 — depth over breadth.
- **B2B data is a Day-1 architectural concern**, not a bolt-on. Anonymization
  boundaries and aggregation pipelines are designed in from the start so the
  Nielsen/IBOPE-style data product is a query away, not a rewrite away.

---

## Architecture

### Domain Model (Planned)

| Aggregate | Purpose |
|---|---|
| **User** | Account, auth, household membership, subscription tier (FREE/PRO), reputation. |
| **Household** | Optional grouping of users that share a purchase history (a couple, a family). |
| **Receipt** (NFC-e) | One scanned invoice. Stores access key (chave de acesso), CNPJ, market, date, total, source URL, raw HTML/JSON snapshot. |
| **ReceiptItem** | One line on a receipt: product, qty, unit, unit price, total, raw description. |
| **Product** (canonical) | Normalized product registry — same item across stores resolves here. Fields: canonical name, brand, EAN/GTIN, category, unit. |
| **ProductAlias** | Free-text descriptions seen on receipts mapped to a canonical Product (NFC-e descriptions are messy and store-specific). |
| **Market** | A retail location — CNPJ + name + address + geocoded point. Multiple receipts roll up here. |
| **PriceObservation** | Anonymized fact: (product, market, date, unit price). The atom of the collaborative price index. Built by the ingestion pipeline from every confirmed receipt. |
| **PricePoint** (aggregated) | Rolling aggregates by (product, market, day/week/month). Denormalized for fast dashboards. |
| **ShoppingList** | A user's planned purchases. Optimizable across nearby markets. |
| **StockEstimate** | Per-(user, product) prediction of when they'll run out, derived from purchase cadence. |
| **Subscription** | Billing record. Tier, period, provider ref. |
| **AnonymizedExport** | Generated B2B data product (aggregated, k-anonymous) — log of what was sold to whom. |

### Services (Planned)

1. **Auth Service** — registration, login, JWT, password reset, email verification, OAuth (Google).
2. **Household Service** — invite, join, leave; switch active household; share/private toggles.
3. **Receipt Ingestion Service** — accept QR string → resolve SEFAZ URL by state → fetch HTML → parse → produce Receipt + ReceiptItems → fan-out to canonicalization and price-index pipelines. **The core feature.**
4. **Product Canonicalization Service** — fuzzy-match raw item descriptions to canonical Products via EAN, alias table, and similarity. Suggests new aliases for review when no match found.
5. **Price Index Service** — write PriceObservations, maintain PricePoint aggregates, expose "best market for this product right now / nearby" queries.
6. **History & Insights Service** — user-facing dashboards: spend per category/month, price evolution per product, top markets, top categories.
7. **Consumption Intelligence Service** — purchase-cadence model per (user, product); shopping-list generation; stock-out alerts; cross-market basket optimization.
8. **Collaboration Service** — anonymized contribution toggle, k-anonymity guarantees, abuse/outlier filtering before facts hit the shared index.
9. **Subscription & Billing Service** — tier management, payment gateway integration, feature gating.
10. **Analytics & Export Service** — B2B aggregated exports, anonymization auditing, partner-facing API.

### NFC-e Ingestion Strategy

- The QR code on every NFC-e encodes a **public SEFAZ URL** that varies by state (RS, SP, MG, etc.). The HTML at that URL contains the full structured invoice (CNPJ, market, items, prices, date, taxes).
- Ingestion pipeline:
  1. Client posts the QR string.
  2. Backend identifies state from the access key (chave de acesso, 44 digits).
  3. State-specific adapter resolves the URL and fetches the HTML.
  4. Parser extracts receipt + items into our domain model.
  5. Async job runs canonicalization and writes PriceObservations.
- Each state adapter is an interface implementation — start with RS (where the user lives), expand by demand.
- Fallback path: manual entry / OCR of printed receipt for damaged QRs (post-MVP).
- Keep the **raw HTML snapshot** per receipt — parsing rules will evolve and we'll want to reprocess.

### Anonymization & Collaboration

- A PriceObservation never carries user_id. It's keyed by (canonical_product_id, market_cnpj, observed_at, unit_price). Source receipt id is kept in a private join table for audit only.
- Aggregation enforces **k-anonymity** at query time: any (product, market, time-window) bucket exposed publicly must have observations from ≥ K distinct users (start K=3).
- Outlier filter: drop observations more than N standard deviations from the rolling median for that (product, market) before they hit the index.
- Users can opt-out of contribution. Their data still powers their own dashboards but never lands in PriceObservation.

### Tech Stack

- Java 21, Spring Boot 4.0.6, Maven
- PostgreSQL (primary store)
- Flyway (database migrations)
- Spring Security + JWT (auth)
- Lombok (boilerplate)
- SLF4J (logging)
- ZXing (QR decode, when needed server-side)
- Jsoup (HTML parsing of SEFAZ pages)
- springdoc-openapi (Swagger UI)
- Bucket4j (rate limiting — added in Phase 2)
- Spring Mail (verification + password reset — added when needed)
- Bean Validation (Jakarta)

---

## Roadmap

The roadmap below maps directly to the product spec (E1–E5). Phases are
deliverable units; ship a phase, then plan the next.

### Phase 0 — Foundations (this session)

- [x] Project bootstrapped (Spring Initializr)
- [ ] CLAUDE.md, HELP.md, MONETIZATION.md committed
- [ ] pom.xml hardened (Flyway, JWT, springdoc, validation, Jsoup, ZXing)
- [ ] application.yml with profile-based config
- [ ] .env.example with required vars
- [ ] i18n scaffolding (messages_en, messages_pt, DomainException, LocalizedMessageService)
- [ ] BaseEntity (id, createdAt, updatedAt)
- [ ] GlobalExceptionHandler
- [ ] Postman collection skeleton with E2E Flow folder
- [ ] First Flyway migration (users table)
- [ ] Git repo initialized, local user config, GitHub remote, initial commit pushed

### Phase 1 — MVP: NFC-e capture (E1 + E2)

**Goal:** A user can register, log in, scan an NFC-e QR, and see the parsed receipt.

- Auth Service (register, login, /me, password change) — port from parkhere
- Household entity (single-user household auto-created on registration; share/invite later)
- Receipt ingestion endpoint: `POST /api/v1/receipts` accepting `{ qrPayload }`
- RS-state SEFAZ adapter (start where the user lives)
- Receipt + ReceiptItem entities, raw HTML snapshot column
- Confirmation flow: parsed receipt returned to user, user confirms or edits items, then it's persisted as `CONFIRMED`
- `GET /api/v1/receipts` (paginated history), `GET /api/v1/receipts/{id}`
- Postman E2E covers: register → login → submit QR → confirm → list → fetch
- i18n for all error messages

**Out of MVP:** product canonicalization, price index, dashboards, predictions, collaboration, multi-state adapters.

### Phase 2 — History & Insights (E3)

**Goal:** Users see their spending and price patterns.

- Product canonicalization V1: EAN match + alias table + manual review queue
- Spend dashboard: by month, by category, by market
- Per-product price evolution chart data endpoint
- Purchase history with filters (date range, market, category)
- Top markets / top categories endpoints
- Migration adds `products`, `product_aliases`, normalized FKs on `receipt_items`

### Phase 3 — Consumption Intelligence (E4)

**Goal:** App tells the user what to buy next, where, and when.

- Purchase-cadence model per (user, product) — simple linear estimate from last N purchases
- Stock-out predictions endpoint
- Suggested shopping list generation
- "Best market for this item near me" — requires market geocoding + the price index
- Basket optimization: given a shopping list, suggest split across nearby markets

### Phase 4 — Collaborative Price Index (E5)

**Goal:** Anonymized contributions power shared price intelligence.

- PriceObservation entity + write path from confirmed receipts
- Opt-in/out contribution toggle on user profile
- k-anonymity-guarded aggregate queries
- Outlier filter on ingestion
- Public reference-price endpoint per product
- "Promo detector": flag prices significantly below the rolling median for that market

### Phase 5 — Monetization (parallel from Phase 2 onward)

See `MONETIZATION.md` for the full strategy. High-level:

- FREE/PRO subscription tier scaffolding (entity + middleware, even if not enforced yet)
- Affiliate-link infrastructure for retailer apps/promotions
- B2B aggregated export endpoints (gated)
- Sponsored placements (with disclosure)

### Phase 6 — Frontend

Backend-only for now. When frontend lands, mirror parkhere (Next.js,
Portuguese-first, Render deployment).

---

## Suggested Additions (Beyond the Spec)

These came up while structuring the project — open for discussion:

- **EAN/barcode scan** for shopping-list items, so users can pre-build lists by scanning packages at home before going shopping.
- **Recipe / meal planning** tied to stock predictions ("you have these items expiring, here are recipes").
- **Personal inflation index** — IPCA-equivalent computed from the user's own basket. Genuinely interesting and shareable.
- **Receipt OCR fallback** for damaged or missing QR codes (post-MVP — Tesseract or a cloud OCR API).
- **Group/household budget split** — when a household has multiple members, allocate the receipt total across them.
- **Brand loyalty / cashback awareness** — surface that retailer X has a cashback app the user isn't using.
- **LGPD compliance plumbing** — data export, account deletion, anonymization audit trail. Non-negotiable for a public Brazilian app handling financial data.

---

## Sensitive Topics

- **LGPD (Brazilian GDPR)** — receipts contain CPF when the user requested it on the receipt. We must (a) detect and strip CPF before persisting where possible, (b) provide data export and account deletion, (c) keep a clear consent record for collaborative contribution.
- **B2B data sales** — must guarantee k-anonymity in any external export. No re-identifiable timestamps, no per-user trails leaking through aggregations. Document the anonymization invariants and test them.
- **Competitive sensitivity** — once retailers notice us, expect pushback. Keep the SEFAZ-based ingestion clearly within the public-data scope it's designed for.

---

## Build & Run

```bash
./mvnw spring-boot:run        # run the app
./mvnw test                    # run tests
./mvnw clean package           # build jar
```

---

## Session Log

### Session 1 (2026-04-27)

- Project initialized via Spring Initializr (Java 21, Spring Boot 4.0.6).
- Defined product spec collaboratively — five epics (E1 NFC-e capture / E2 Auth / E3 History & Insights / E4 Consumption Intelligence / E5 Collaborative Base).
- Decided: backend only for now, multi-tenant public app, mirror parkhere conventions for CLAUDE.md, Flyway+Postgres, JWT auth, en+pt i18n.
- Decided: monetization architected from Day 1 — see MONETIZATION.md.
- Wrote CLAUDE.md, HELP.md, MONETIZATION.md.
- Hardened pom.xml and added base scaffolding (i18n, db/migration, postman folders).
- Initialized git, configured local user (Alexandre Vieira / xandivieira@gmail.com), prepared GitHub remote at https://github.com/XandiVieira/economiz.AI.git.
