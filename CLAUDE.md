# economizai - Project Conventions

## Overview
economizai is a collaborative price-intelligence app built on Brazilian NFC-e
receipts — users scan their grocery receipt QR codes, the system parses every
line item, and the anonymized result powers both personal dashboards and a
shared price index. Built with Java 21 + Spring Boot 4. See `HELP.md` for
vision, architecture, and roadmap; `MONETIZATION.md` for revenue strategy.

GitHub repo: `economiz.AI` (https://github.com/XandiVieira/economiz.AI.git)

## Tech Stack
- Java 21, Spring Boot 4.0.6, Maven
- PostgreSQL
- Flyway (database migrations)
- Spring Security + JWT (authentication)
- Lombok (boilerplate reduction)
- SLF4J (logging)
- ZXing (server-side QR decoding when needed)
- Jsoup (HTML parsing of SEFAZ NFC-e pages)
- springdoc-openapi (Swagger UI)

## Code Style

### General
- Use `var` for local variables
- Prefer functional style (streams, lambdas, Optional chaining) when reasonable
- Minimal comments — only when logic is non-obvious
- No unnecessary abstractions or premature generalization
- Domain language is Brazilian Portuguese where it's a legal/domain term (NFC-e, CNPJ, CPF, SEFAZ, chave de acesso) — do not translate these

### Logging
- Use SLF4J (`org.slf4j.Logger` / `org.slf4j.LoggerFactory`) via `@Slf4j` Lombok annotation
- Every feature must have relevant log entries (info for business events, debug for flow, warn/error for failures)
- Use parameterized messages: `log.info("Receipt {} parsed for user {} with {} items", receiptId, userId, itemCount)`
- Never log raw CPF, full receipt access keys, or JWTs — mask if needed
- **MDC correlation:** every request log line carries `req=<8-char>`, `user=<email>`, plus `rcpt=<8-char>` and `item=<8-char>` when the flow touches a receipt or item. To trace one receipt end-to-end, grep by `rcpt=<id>`. Set MDC via `MDC.put(MdcContextFilter.RECEIPT_ID, ...)` etc. — `MdcContextFilter` clears it at request end.
- **One INFO line per decision, not per loop iteration.** Use the format `<event>.<outcome> key1=value1 key2=value2`. Examples: `item.created_from_ean`, `item.matched_by_alias`, `item.unmatched`, `submit ok`, `confirm ok`. Aggregates (`canonicalize done matched=X created=Y`) come at the end as a separate INFO.

### Testing
- All new code must be covered by relevant unit tests
- Tests live in `src/test/java` mirroring the main package structure
- Use JUnit 5 + Mockito for unit tests
- Use `@DataJpaTest` for repository tests, `@WebMvcTest` for controller tests
- Receipt parsers must be covered by tests with real SEFAZ HTML fixtures (saved under `src/test/resources/fixtures/sefaz/<state>/`)

### API & Postman
- All APIs are versioned: `/api/v1/...`
- A Postman collection is maintained at `postman/economizai.postman_collection.json`
- Every endpoint change (create, update, remove) must update the Postman collection
- The collection includes an **E2E Flow** folder — a sequential test suite that runs all requests in logical order, each setting data for the next. This must also be updated on any endpoint change.
- REST endpoints follow standard conventions: plural nouns, proper HTTP methods

### Internationalization (i18n)
- All user-facing messages use i18n via Spring `MessageSource`
- Message files: `src/main/resources/i18n/messages_en.properties`, `messages_pt.properties`
- Exceptions extend `DomainException` with a `messageKey` and optional `arguments`
- `LocalizedMessageService` translates using `LocaleContextHolder` (resolved from `Accept-Language` header)
- Default locale: Portuguese (pt) — this is a Brazilian product
- Add new message keys to both `_en` and `_pt` properties files

### Database
- Flyway migrations in `src/main/resources/db/migration`
- Migration naming: `V{number}__{description}.sql`
- Never modify existing migrations — create new ones
- Money fields use `NUMERIC(12,2)` (R$). Quantity fields use `NUMERIC(12,3)`.
- Timestamps in `TIMESTAMP WITH TIME ZONE`, default `now()`

### Privacy & Anonymization (LGPD)
- `PriceObservation` (the collaborative index atom) **must never carry user_id** in its primary table
- Audit trail (which receipt produced which observation) lives in a separate, internal-only join table
- Aggregate queries exposed publicly or to B2B clients enforce **k-anonymity** (start K=3) at the query layer
- CPF, when present on receipts, is stripped before raw HTML is persisted (regex sweep on the snapshot)
- Any new endpoint that exposes aggregated data must have a test asserting k-anonymity holds

### Monetization Hooks
- Every feature scoped to FREE vs PRO must respect `User.subscriptionTier`
- New paywalls go through a single gating service — never inline `if (user.tier != PRO)` in controllers
- See `MONETIZATION.md` for the tier matrix

### Git & Commits
- **Atomic commits** — each commit should represent one logical change (one feature, one fix, one refactor)
- Do not bundle unrelated changes in the same commit
- **Never mention Claude, AI, or any co-author in commit messages** — no `Co-Authored-By` lines, no references to AI assistance
- This is a personal project on a professional MacBook — git user is configured locally per-repo to avoid mixing accounts
- Local config: `user.name = Alexandre Vieira`, `user.email = xandivieira@gmail.com`
- Remote: `https://github.com/XandiVieira/economiz.AI.git`
- Never touch the global git config

## Project Documentation
- `HELP.md` is the project development log — vision, architecture, phased roadmap, session log. Update on significant progress, decisions, or architectural changes.
- `MONETIZATION.md` is the living revenue strategy. Update when pricing, tiers, or B2B plans evolve.

## Build & Run
```bash
./mvnw spring-boot:run        # run the app
./mvnw test                    # run tests
./mvnw clean package           # build jar
```
