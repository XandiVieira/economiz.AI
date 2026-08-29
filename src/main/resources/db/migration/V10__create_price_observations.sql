-- LGPD: PriceObservation NEVER carries user_id, household_id, or any
-- identifier that could re-link an observation to a person. The audit
-- trail (which receipt produced which observation) lives in the
-- internal-only price_observation_audit table.
--
-- Denormalized columns (market_name, market_city, market_state) make
-- aggregate queries fast without joining receipts/users.

CREATE TABLE price_observations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    market_cnpj VARCHAR(14) NOT NULL,
    market_cnpj_root VARCHAR(8) NOT NULL,
    market_name VARCHAR(255),
    unit_price NUMERIC(12,4) NOT NULL,
    quantity NUMERIC(12,3) NOT NULL,
    pack_size NUMERIC(10,3),
    pack_unit VARCHAR(10),
    observed_at TIMESTAMP NOT NULL,
    is_outlier BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_price_observations_product_market ON price_observations(product_id, market_cnpj);
CREATE INDEX idx_price_observations_product_chain ON price_observations(product_id, market_cnpj_root);
CREATE INDEX idx_price_observations_observed_at ON price_observations(observed_at DESC);
CREATE INDEX idx_price_observations_market_cnpj ON price_observations(market_cnpj);

-- Internal-only audit table: links each anonymized observation back to
-- the receipt + household that contributed it. Used for:
--   1. K-anonymity counting (how many distinct households contributed?)
--   2. Right-to-deletion (cascade-delete observations when household is
--      removed, IF the household opted out — handled in service layer)
--   3. Internal investigation only — NEVER exposed via the API.
CREATE TABLE price_observation_audits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    observation_id UUID NOT NULL UNIQUE REFERENCES price_observations(id) ON DELETE CASCADE,
    receipt_id UUID NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    contributed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_price_observation_audits_household ON price_observation_audits(household_id);
CREATE INDEX idx_price_observation_audits_receipt ON price_observation_audits(receipt_id);
