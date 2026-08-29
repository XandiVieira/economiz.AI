-- Per-household custom market display names. A household can rename a market
-- (by CNPJ); that name is shown only to that household, everywhere a market
-- appears. Mirrors household_product_aliases (per-household product naming).
-- Global market_locations.name is never modified.
CREATE TABLE household_market_aliases (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    market_cnpj  VARCHAR(14) NOT NULL,
    custom_name  VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (household_id, market_cnpj)
);

CREATE INDEX idx_household_market_aliases_household ON household_market_aliases(household_id);
