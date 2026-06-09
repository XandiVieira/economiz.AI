# SonarCloud — analysis & triage log

Dashboard: https://sonarcloud.io/project/overview?id=XandiVieira_economiz.AI
Analysis runs in CI (`.github/workflows/sonar.yml`) on every push/PR to `development`,
compiling the project (`mvn verify`) so Sonar gets full type resolution + JaCoCo coverage.

This file records what we fixed, what we deliberately accepted, and why — so the
next person doesn't re-litigate each finding. Severities use Sonar's MQR model,
where **each rating = the worst-severity issue present** (INFO does not lower a rating).

---

## Baseline (first automatic analysis, 2026-06-08)
Security A · Reliability **E** · Maintainability A · Hotspots **E (0% reviewed)** · Coverage n/a
342 issues: 2 BLOCKER, 20 HIGH, 26 MEDIUM, 88 LOW, 206 INFO.

The two E ratings were driven by a tiny number of real items plus one false positive —
not by the headline counts.

---

## Fixed in code

### Reliability (the path to Reliability A)
| Rule | Where | Was | Fix |
|------|-------|-----|-----|
| **S2479** (real bug) | `AdminProductService` dedup key | separators were literal **NUL bytes** (`"\0"`), not spaces — grouping key was `name\0brand\0…` | replaced the 3 NULs with real spaces. Scanned the whole tree; no other stray NULs. |
| S8700 | `ConsumptionIntelligenceService:189,223` | `Duration.between(a.atStartOfDay(), b.atStartOfDay()).toDays()` (DST-fragile) | `ChronoUnit.DAYS.between(a, b)` directly on `LocalDate` — clearer and correct. |
| S2184 | `CommunityPromoService:134`, `PromoDetector:59` | `BigDecimal.valueOf(100 - pct)` int subtraction | widened to `100L - pct`. No real overflow (pct is 0–100) but makes the widening explicit. |
| S5866 | `SvrsSharedPortalAdapter` `EMISSION` regex | `CASE_INSENSITIVE` on an accented `Emiss[aã]o` literal | added `Pattern.UNICODE_CASE` so `ã/Ã` case-folds correctly. |

### Security Hotspots (→ Hotspots A)
| Rule | Where | Fix |
|------|-------|-----|
| S5852 (regex DoS) ×5 | `PackSizeExtractor:8,12`, `SvrsSharedPortalAdapter:46,47,51` | hardened the ambiguous `\s*`/`\s+` runs with **possessive quantifiers** (`\s*+`, `\d++`). Eliminates backtracking; semantics unchanged (whitespace is always followed by a disjoint class, so greedy never needed to backtrack). Covered by the SEFAZ fixture tests. |

### Maintainability — BLOCKER + HIGH
| Rule | Where | Fix |
|------|-------|-----|
| **S1845** (BLOCKER) | `ProductExtraction` | removed the redundant `empty()` method (clashed with the `EMPTY` field by case only); callers now use `ProductExtraction.EMPTY`. |
| S1192 ×7 | various | extracted duplicated string literals to named constants (`VALIDATION_FAILED_KEY`, `HOUSEHOLD_MISSING_MSG`, `ORDER_BY_TOTAL_DESC`, `ENUM_PREFIX`, `BRASIL_SUFFIX`, `MILLI`, and reuse of `FALLBACK_CONTENT_TYPE`). |
| S131 | `CanonicalizationService:56` | added a `default ->` that throws, to catch any future enum constant. |
| S1186 | `EconomizaiApplicationTests.contextLoads` | documented the intentionally-empty Spring smoke test. |

---

## Deliberately NOT changed (verified, with reasons)

### S2275 — `DigestService:52` "An 'int' is expected rather than a Object" (BLOCKER, reliability)
**False positive.** The arg is a `long` (`countBy…` returns `long`) and `%d` accepts `long`.
The *automatic* analysis doesn't compile the code, so it couldn't resolve the type and
assumed `Object`. The **CI analysis compiles**, so this clears itself — no code change.

### S6218 ×2 — `ProfilePictureService` records holding `byte[]` (MEDIUM, reliability)
`ProcessedImage` / `ProfilePictureBytes` are write-once carriers; their value-equality is
never invoked (never used as map keys or compared). Overriding `equals/hashCode/toString`
to deep-compare image bytes would be dead code implying a semantics we don't use.
**Marked "won't fix" in Sonar** rather than adding ceremony.

### S3776 ×8 — cognitive complexity (HIGH, maintainability)
Maintainability is already **A** (these don't change the rating). Refactoring 8 methods
(`CommunityPromoService` cc=46, `ReceiptSpecifications`, `JaroWinklerSimilarity`,
`AutoPromotionService`, `CategorizationBenchmarkService`, `ProductExtractor`,
`NotificationRuleEngine`, `PriceIndexService`) carries real behavior-change risk and
deserves focused, individually-tested PRs — not a bundled lint sweep. **Deferred** as a
tracked backlog item; tackle opportunistically when touching each method.

### Opinionated "java.time" rules — 268 issues, all INFO/LOW (no rating impact)
- **S8692** (135, INFO) "Do not use the system clock in tests"
- **S8688** (70, INFO) "Pass a ZoneId/Clock to .now()"
- **S8694** (63, LOW) "Use java.time.Month enum instead of an int literal"

Using real time in tests and `LocalDateTime.now()` in a single-region (America/Sao_Paulo)
app is idiomatic; rewriting 268 call sites to inject clocks/zones is disproportionate churn
for zero rating benefit. **Accepted.** If we ever want the issue count at literal zero,
deactivate these three rules in a custom Quality Profile rather than churn the code.

### Remaining MEDIUM/LOW backlog (accepted for now)
S4144 (identical `UserDetails` boolean methods — idiomatic), S6213, S3358 (nested ternaries),
S135 (break/continue), and assorted singletons. None affect a rating; revisit if/when the
Quality Gate on *new* code flags them.

---

## Wave 2 — issues the *compiled* CI analysis surfaced (invisible to the old auto-scan)

Switching to CI-based (compiled) analysis exposed real bugs the uncompiled
automatic scan could not see (no bytecode → no taint/transaction analysis):

| Issue | Where | Fix |
|------|-------|-----|
| **S2083/S6549** path traversal (2 vulns) | `LocalDiskProfilePictureStorage.read()/delete()` | `key` was resolved against the storage dir unchecked (`../../etc/passwd` escapes). Added `resolveWithinBase()` — normalize + reject anything outside `baseDir`. + traversal tests. |
| **S2229** @Transactional self-invocation (6 BLOCKER bugs) | `MarketLocationService` (geocodeOne, classifySegmentOne), `AutoPromotionService`, `ConsensusPromotionService`, `MlClassifierService`, `CommunityPromoService` | Methods called their `@Transactional` siblings via `this`, bypassing the Spring proxy so the annotation never applied. Fixed via **self-injection** (`@Lazy @Autowired Self self = this`) and calling through `self.` — defaults to `this` so unit tests run unchanged. |
| **5 security hotspots** | SecurityConfig CSRF, 3× dynamic JPQL in InsightsQueryService, 1× regex | Reviewed + marked **Safe** with justifications: CSRF-off is correct for a stateless JWT API; the JPQL interpolates only fixed skeletons + closed `Dimension` enum constants (all user values bound as named params — verified in `buildClauses`); regex is possessive-hardened on short input. |

## Target end state
Security **A** · Reliability **A** · Maintainability **A** · Hotspots **A (100% reviewed)** ·
Quality Gate **Passed** on new code. "Clean as You Code": don't zero the historical backlog —
keep *new* code clean and let the gate enforce it per PR.
