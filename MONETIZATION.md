# economizai — Monetization Strategy

Monetization is treated as a **Day-1 architectural concern**, not a Phase-N
afterthought. The entities, anonymization boundaries, and feature gates listed
below should appear in the data model from the first migration that touches
their area, even if the actual paywall is unenforced for months.

## Revenue Models (by priority)

### 1. Freemium — economizai Pro (R$9.90/month, R$89/year)

**Pricing rationale.** Anchor against streaming (R$25–R$45/mo) and Nubank Ultravioleta
(R$24/mo) — clearly less than one streaming service. Annual pricing offers ~25%
discount to reduce churn. The "I save R$X/mo on groceries" pitch needs the price
small enough that the comparison is unflattering — R$9.90 is "the difference is
one box of milk".

#### Built feature → tier matrix (as of 2026-05-01)

This maps what's *already shipped* to where it should live. Anything in the
"Future" column is on the roadmap but not built yet; columns are aspirational.

| Capability | FREE | PRO | Notes |
|---|---|---|---|
| Submit NFC-e receipt | ✅ (5/month) | ✅ unlimited | Counter middleware, easy gate. Heavy users hit 5 in a week. |
| Receipt history | last **90 days** | unlimited | Query-layer gate on `issuedAt`. Cheap to implement, painful to discover (user opens app, sees their old data is gone). |
| Spend dashboard `/insights/spend` | current month only | full range | Same gate as history. |
| Top markets / categories | top 3 | top 10+ | Hard cap on `?limit` for FREE. |
| Price history per product | current month | full | Same gate. |
| Reference price (community) | ✅ | ✅ | Collaborative read — keep open to drive panel value perception. |
| Best markets ranking | top 3 nearby | top 10 + watchlist + radius filter | Watchlist is FREE for first 3 markets, unlimited for PRO. |
| Watched markets | up to **3** pinned | unlimited | Concrete, easy gate, real upgrade pressure for power users. |
| Community promos (`/price-index/promos`) | last 7 days | full lookback + push | Same data, different freshness window. |
| Personal promo detection | ✅ on confirm | ✅ on confirm | Free — it's the magic-moment hook on every receipt. |
| Personal promo notifications | in-app only | push + email | The "alert me when my common stuff drops" loop. |
| Consumption predictions (`/consumption/predictions`) | view-only | view + push when "RUNNING_LOW" | Predictions are visible, the *push* is paid. |
| Suggested shopping list | view-only | view + basket optimization across markets | Optimization is the hard PRO sell — turns a list into "go to market A for these, B for those, save R$Y". |
| Auto-derived preferences (`/preferences`) | view-only | view + manual override (AVOID/MUST_HAVE) | Auto-derivation for free, manual control paid. AVOID specifically can't be auto-derived → natural gate. |
| Household sharing | up to **2 users** | up to 6 (Family plan, see §5) | Need a member-count gate at household join. |
| Notification preferences | per-type channel choice | + custom alert rules per product | "Alert me when leite Italac < R$6 anywhere within 5km" — power feature. |
| Data export (LGPD) | ✅ | ✅ | LGPD-mandated, never gated. |
| Account deletion | ✅ | ✅ | LGPD-mandated. |
| Ads on dashboards | shown | hidden | See §4. |
| Personal inflation index | — | ✅ | Compute basket-level IPCA equivalent. Real-time, beats government numbers. Differentiator. |
| Recipe-based shopping | — | ✅ | Input recipes, get optimized list per recipe. Future feature. |
| CSV/Parquet export of own data | — | ✅ | Power users + tax / accounting use case. |

**The PRO pitch in one sentence:** "Pay R$9.90/mo to get unlimited history,
push alerts when your usual stuff is on sale, automatic shopping lists optimized
across markets, and basket-level inflation tracking."

**Why these gates work for grocery shoppers specifically:**
- A weekly grocery shopper hits the 5-receipt cap in week 1 → forced to choose.
- A new user signs up, scans a receipt, sees "you usually pay R$28 for this, you
  paid R$22 today — save R$6/mo on average if you keep coming here" → magic
  moment. That stays free; everything that compounds it is paid.
- The "alert me when X drops" feature is exactly the loop that gets users to
  reopen the app — and it's also the most friction-y to wire (FCM, email, rules
  engine), so paywalling it has good unit economics.

#### Implementation needed
- `User.subscriptionTier` (FREE/PRO) — already on entity, *not enforced anywhere yet*.
- Single `SubscriptionGateService` — never inline `if (user.tier != PRO)` in
  controllers (per CLAUDE.md). Service gives back `Allowed | Blocked(reason, upgradeUrl)`.
- Receipt upload counter — count by month per user.
- Query-layer date-range cap — PRO bypasses, FREE truncates.
- Watched-markets gate — `WatchedMarketService.watch` checks count.
- Push-notification gate — `NotificationService` reads tier before dispatching
  push for PROMO_PERSONAL / PROMO_COMMUNITY / RUNNING_LOW.
- Stripe Brasil or Pagar.me + Pix integration.
- Subscription-management page + PUT `/users/me/subscription`.
- Feature-flag service so we can A/B individual gates.

#### Gating sequence (which paywalls to ship first)
1. **Watched markets cap** (3 free) — easiest gate, immediately visible, low risk.
2. **History window** (90 days free) — wide impact, simple query change.
3. **Push notifications** (PRO only) — high value, requires FCM live first.
4. **Basket optimization** (PRO only) — hard to build, highest perceived value.
5. **Manual preference override** (PRO only) — depends on Phase 2.6 completion.

Don't ship all five at once. Each gate is an A/B; measure conversion before
adding the next.

---

### 2. B2B Anonymized Price Intelligence (the moat — R$5K–R$100K/year)

This is the bigger lever long-term. We're sitting on real-world FMCG transaction
data — what Nielsen/IBOPE/NielsenIQ sell to brands and retailers for serious
money. The collaborative anonymized index is the product.

**Data products:**
- **Brand share of shelf** — for a given category in a given region, how
  spending is distributed across brands (week-over-week trends).
- **Promo effectiveness** — when retailer X dropped product Y's price by Z%,
  how did volume shift across competing markets in the same region?
- **Regional price dispersion** — same product, same week, price spread across
  markets and neighborhoods. Real arbitrage signal.
- **Category basket trends** — what's growing, what's declining, by region.
- **Cross-retailer comparison panels** — anonymized but representative
  baskets compared across retailer brands.

**Buyers:**
- FMCG manufacturers (Unilever, Nestlé, Ambev, Coca-Cola, BRF) — pricing intel and competitive benchmarking.
- Retail chains (regional markets, Carrefour, Assaí, Atacadão) — price gap analysis vs. competitors.
- Market research firms (NielsenIQ, Kantar, Euromonitor) — supplementary panels.
- Universities and economists — academic licensing, possibly free-tier.
- News media — periodic "cesta básica" reporting (low-revenue, high-marketing).

**Revenue model:**
- Annual data subscription: R$5K (regional starter) → R$100K+ (national, multi-category).
- Custom one-off reports: R$2K–R$20K.
- API access for live dashboards: tiered.

**Implementation needed:**
- Anonymization pipeline with **k-anonymity** invariants (already on Phase 4 roadmap — make it production-grade).
- Aggregation tables (PricePoint by week × market × product × region).
- B2B partner portal (separate auth realm, signed contracts).
- Export API (CSV/Parquet) with audit log.
- LGPD compliance documentation pack — non-negotiable.
- Sales materials and a "free sample" report generator.

**Why this is strategic:** The personal product builds the panel for free
(every user wants their dashboard). The B2B side monetizes the panel without
extracting more from users. This is the "Robinhood with payment for order flow"
shape — the consumer side is free or cheap, the data is the actual product.
Just be transparent about it from Day 1 (anonymization, opt-out, user-visible
explainer).

---

### 3. Affiliate Links to Retailer Apps & Promos (R$0.10–R$5 per click/install)

When the system says "buy product X at market Y", and Y has a cashback app or
loyalty program (Méliuz, Picpay, market-native apps), surface a deep link.

**Revenue:**
- Affiliate commission per app install (R$3–R$10).
- Per-click commission on cashback platforms (R$0.10–R$1).
- Per-conversion commission on partnered retailers.

**Implementation needed:**
- Affiliate link table per (market, product?, retailer-app).
- Deep-link generator with affiliate IDs.
- Click-tracking middleware.
- Disclosure: "Patrocinado" badge.

---

### 4. Sponsored Placements (with disclosure)

A market or brand can pay to be highlighted in shopping-list suggestions or
price comparisons.

**Ad formats:**
- "Recommended for this list" sponsored card.
- Highlighted product card in price comparison ("Patrocinado pelo fabricante").
- Banner in dashboard (free users only).

**Constraint:** Must never compromise the integrity of the price index. A
sponsored placement can surface a market in the UI but must never alter the
ranking the user sees as "cheapest" — that ranking stays purely data-driven.
This trust is the entire product.

**Revenue:** CPM or CPC, R$1–R$5 per click for free users.

**Implementation needed:**
- Sponsorship slot components and explicit data-rank vs. promoted-rank separation.
- Disclosure UI ("Patrocinado") — never optional.
- Pro users skip all sponsored placements.

---

### 5. Premium Family / Business Plans (later)

- **Family plan (R$19.90/month, up to 5 users)** — shared household, split
  budgets, individual dashboards.
- **Small business plan (R$49.90/month)** — for tiny restaurants, snack bars,
  food trucks who buy at the same supermarkets — track COGS via NFC-e, get
  category benchmarks against the public index.

---

## Implementation Roadmap

### Phase 1 — Build the Panel (current)

- **Goal:** 100 active users in Porto Alegre, ingesting receipts weekly.
- **Focus:** Free product, low friction, fast MVP.
- **Monetization:** None. Pure growth. Subscription tier field exists but is unenforced.
- **Key metric:** Weekly receipts uploaded per user (the panel quality metric).

### Phase 2 — Validate Willingness to Pay (1K MAU)

- **Goal:** 5–10% of users accept a Pro plan.
- **Features to add:**
  - Subscription gating (history range, upload count, predictions).
  - Stripe/Pagar.me + Pix integration.
  - Subscription management page.
  - Ad slots on dashboard for free users.
- **Revenue target:** R$1K–R$5K MRR.

### Phase 3 — Open the B2B Channel (5K MAU, ≥3 cities)

- **Goal:** First paying B2B customer.
- **Features to add:**
  - Aggregation pipeline production-grade (k-anonymity tested).
  - B2B portal + sample report generator.
  - LGPD documentation pack.
  - Sales-friendly category / region / time-window export API.
- **Revenue target:** R$10K–R$30K MRR (Pro + 1–2 B2B subs).

### Phase 4 — Scale Both Sides (20K+ MAU)

- **Goal:** Multi-state coverage, recurring B2B revenue.
- **Features:**
  - SEFAZ adapters for all major states.
  - Multi-region B2B reporting.
  - Family plans + small-business plans.
  - Affiliate-link program at scale.
- **Revenue target:** R$80K+ MRR.

---

## Competitive Moat

### Why Apple/Google/Nubank won't kill this

1. **Focus asymmetry** — receipts as pricing data is our entire product, their 0.01% feature.
2. **Brazilian-specific rail** — NFC-e + per-state SEFAZ adapters. Foreign products do not have this primitive; they all assume manual entry or bank-statement parsing.
3. **Data depth as a moat** — once we have a year of cross-region, cross-retailer transaction data, even an incumbent walking in has to start from zero.
4. **Two-sided product** — consumer panel funds the data product, data product subsidizes consumer features. Hard to replicate without one of the sides already in motion.
5. **Speed** — we ship pricing features weekly, FMCG/retail incumbents ship them yearly.

### Real risks

1. **Cold start** — need ~100 weekly active reporters per region for the index to be useful. Niche-down hard at first (Porto Alegre supermarkets).
2. **SEFAZ availability** — public NFC-e pages can be flaky. Cache, retry, and tolerate.
3. **Retailer pushback** — a chain might dislike being publicly compared. Stay clearly within the public-data legal scope.
4. **LGPD missteps** — one re-identification incident kills trust permanently. Get this right before the B2B channel opens.
5. **Direct competitor with better distribution** — a bank app or fintech adding receipt parsing. Defense: be deeper and more open than they ever will be.

### Defense strategy

- Own Porto Alegre's supermarket data first. Then RS state. Then expand.
- Be transparent about the anonymization model — publish the invariants.
- Build community loyalty via the personal product — make Pro genuinely save money.
- Consider open-sourcing parts of the parsing layer to lock in standards.
- If acquired by a NielsenIQ-equivalent or a Brazilian fintech — that's a realistic exit.

---

## Key Metrics to Track

| Metric | Phase 1 Target | Phase 2 Target | Phase 3 Target | Phase 4 Target |
|---|---:|---:|---:|---:|
| Monthly active users (MAU) | 100 | 1,000 | 5,000 | 20,000 |
| Receipts ingested / week | 200 | 3,000 | 20,000 | 100,000 |
| Distinct markets covered | 30 | 300 | 1,500 | 5,000 |
| Pro subscribers | — | 50 | 500 | 2,500 |
| B2B clients | — | — | 1–2 | 10+ |
| MRR | R$0 | R$2K | R$25K | R$80K+ |

The receipts/week metric is the one to obsess over — it's the panel quality
signal that everything else (Pro retention, B2B sellability) ultimately
depends on.
