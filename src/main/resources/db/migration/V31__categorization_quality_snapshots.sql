-- Time series of categorization quality. One row is written every time the
-- benchmark runs or a re-categorization backfill is applied, so we can see
-- whether categorization is improving or regressing over time.
--
--  - accuracy_pct: % of the golden set the cascade categorized correctly
--  - catalog_coverage_pct: % of real products that have a category at all
--  - ml_ready: whether the ML model was trained at snapshot time (context)
CREATE TABLE categorization_quality_snapshots (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger              VARCHAR(20) NOT NULL,
    accuracy_pct         NUMERIC(5,2) NOT NULL,
    benchmark_total      INTEGER NOT NULL,
    benchmark_correct    INTEGER NOT NULL,
    catalog_products     INTEGER NOT NULL,
    catalog_categorized  INTEGER NOT NULL,
    catalog_coverage_pct NUMERIC(5,2) NOT NULL,
    ml_ready             BOOLEAN NOT NULL,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_catq_created_at ON categorization_quality_snapshots(created_at DESC);
