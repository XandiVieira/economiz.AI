-- Per-household manual override of the auto-derived brand preference.
-- One row per (household, generic_name): the user says "for milk, my brand
-- is Itambé" and we honor that even when history says otherwise. Strength
-- is set explicitly by the user (PREFERRED vs MUST_HAVE).

CREATE TABLE manual_brand_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    generic_name VARCHAR(255) NOT NULL,
    brand VARCHAR(255) NOT NULL,
    strength VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_manual_brand_preferences_household_generic UNIQUE (household_id, generic_name)
);
CREATE INDEX idx_manual_brand_preferences_household ON manual_brand_preferences(household_id);
