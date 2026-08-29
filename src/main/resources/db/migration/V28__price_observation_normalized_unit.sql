-- Adds price-per-base-unit alongside the raw unit price so cross-pack /
-- cross-spelling comparisons are honest (R$/L of milk, R$/kg of arroz)
-- regardless of how the receipt expressed quantity & unit.
--
-- Both fields nullable: when neither the item unit nor the product's
-- pack info maps to a known base unit, leave null and consumers fall
-- back to the raw unit_price (current behavior).
--
-- No backfill in SQL: the conversion table lives in Java
-- (UnitConverter.CONVERSIONS) and we'd duplicate it here. Existing rows
-- stay null until they're recomputed by a future write or an explicit
-- backfill job. Acceptable because (a) we're pre-launch with mostly dev
-- data and (b) consumers degrade gracefully.

ALTER TABLE price_observations ADD COLUMN normalized_unit_price NUMERIC(12,4);
ALTER TABLE price_observations ADD COLUMN normalized_unit VARCHAR(8);
CREATE INDEX idx_price_observations_normalized_unit ON price_observations(normalized_unit) WHERE normalized_unit IS NOT NULL;
