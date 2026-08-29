-- Two pieces working together so the user can rename items for display
-- without losing the original NFC-e text:
--
-- 1. receipt_items.friendly_description — per-item display override.
--    Optional; FE shows it when set, falls back to raw_description.
--    raw_description stays immutable (it's the audit trail from SEFAZ).
--
-- 2. household_product_aliases — household-level memory of "this is what
--    we call this product". Keyed by (household_id, product_id) so the
--    same Product (canonical) can have different display names per
--    household. When a user names an item, we save it here. On future
--    receipts that link to the same product, canonicalization picks it
--    up automatically — user only types the name once.

ALTER TABLE receipt_items ADD COLUMN friendly_description VARCHAR(500);

CREATE TABLE household_product_aliases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    friendly_name VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (household_id, product_id)
);
CREATE INDEX idx_household_product_aliases_household ON household_product_aliases(household_id);
