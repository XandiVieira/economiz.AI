-- A household's manual category correction for a product. This is "evidence,
-- not truth": it overrides the category THAT household sees for the product
-- (the global product is untouched, so other households are unaffected), and
-- each row is a vote that a later consensus pass can use to refine the global
-- default. One row per (household, product).
CREATE TABLE household_product_category_overrides (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    product_id   UUID NOT NULL REFERENCES products(id),
    category     VARCHAR(30) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (household_id, product_id)
);

CREATE INDEX idx_hpco_household ON household_product_category_overrides(household_id);
-- product + category supports the future cross-household consensus query.
CREATE INDEX idx_hpco_product_category ON household_product_category_overrides(product_id, category);
