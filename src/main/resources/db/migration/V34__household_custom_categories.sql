-- User-defined categories, scoped to a household (e.g. "FRUITS"). They overlay
-- the global enum: the household can migrate its products into them. A product's
-- category override now targets EITHER a global enum (category) OR a custom
-- category (custom_category_id) — exactly one is set.
CREATE TABLE household_custom_categories (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name         VARCHAR(60) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (household_id, name)
);

-- Let an override point to a custom category; make the enum nullable accordingly.
ALTER TABLE household_product_category_overrides
    ADD COLUMN custom_category_id UUID REFERENCES household_custom_categories(id) ON DELETE CASCADE;
ALTER TABLE household_product_category_overrides
    ALTER COLUMN category DROP NOT NULL;
