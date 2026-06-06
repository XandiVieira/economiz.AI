-- Track brand + quantity extraction quality alongside category, plus a shadow
-- measurement of the ML model's accuracy (even while it's gated out of the live
-- cascade) so we can tell when it's good enough to re-enable.
ALTER TABLE categorization_quality_snapshots
    ADD COLUMN brand_accuracy_pct    NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN quantity_accuracy_pct NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN ml_accuracy_pct       NUMERIC(5,2) NOT NULL DEFAULT 0;
