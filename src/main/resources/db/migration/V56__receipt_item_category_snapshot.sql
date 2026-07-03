-- Category snapshot at confirmation: "new knowledge only affects new entries".
-- Consensus/backfill recategorizations keep improving the LIVE product category
-- (future purchases inherit it), but each household's confirmed history stays
-- as it was seen at confirm time. Explicit user overrides still win over both.
ALTER TABLE receipt_items
    ADD COLUMN category_at_confirmation VARCHAR(30);

-- Backfill existing confirmed history with the current product category — the
-- best available approximation of what the user saw.
UPDATE receipt_items ri
SET category_at_confirmation = p.category
FROM products p, receipts r
WHERE ri.product_id = p.id
  AND ri.receipt_id = r.id
  AND r.status = 'CONFIRMED'
  AND p.category IS NOT NULL;
