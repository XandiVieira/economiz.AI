-- Household merge/split provenance (Phase 0).
-- Every movable household-scoped row gets an immutable origin_household_id = the
-- household that ORIGINALLY owned it. household_id stays "where it currently lives"
-- (rewritten on merge); origin_household_id is "home" (never rewritten), so a split
-- can restore each person's data to where it came from. Backfill = current household.
--
-- The column is NOT NULL (provenance must always exist) and the FK is ON DELETE
-- RESTRICT (the default): a household that is some row's origin CANNOT be deleted.
-- This is the DB-level backstop for the same invariant the HouseholdService deletion
-- guard enforces in code — a household that still owns data (current OR origin) is
-- never deleted, so provenance can never dangle.

ALTER TABLE receipts                            ADD COLUMN origin_household_id UUID;
ALTER TABLE manual_purchases                    ADD COLUMN origin_household_id UUID;
ALTER TABLE shopping_lists                       ADD COLUMN origin_household_id UUID;
ALTER TABLE consumption_snoozes                  ADD COLUMN origin_household_id UUID;
ALTER TABLE household_product_category_overrides ADD COLUMN origin_household_id UUID;
ALTER TABLE household_custom_categories          ADD COLUMN origin_household_id UUID;
ALTER TABLE household_product_aliases            ADD COLUMN origin_household_id UUID;
ALTER TABLE household_market_aliases             ADD COLUMN origin_household_id UUID;
ALTER TABLE manual_brand_preferences             ADD COLUMN origin_household_id UUID;

-- Backfill: existing rows originated in their current household.
UPDATE receipts                            SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE manual_purchases                    SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE shopping_lists                       SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE consumption_snoozes                  SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE household_product_category_overrides SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE household_custom_categories          SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE household_product_aliases            SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE household_market_aliases             SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE manual_brand_preferences             SET origin_household_id = household_id WHERE origin_household_id IS NULL;

-- Enforce NOT NULL now that everything is backfilled, and wire the FK.
ALTER TABLE receipts                            ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE manual_purchases                    ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE shopping_lists                       ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE consumption_snoozes                  ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE household_product_category_overrides ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE household_custom_categories          ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE household_product_aliases            ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE household_market_aliases             ALTER COLUMN origin_household_id SET NOT NULL;
ALTER TABLE manual_brand_preferences             ALTER COLUMN origin_household_id SET NOT NULL;

ALTER TABLE receipts                            ADD CONSTRAINT fk_receipts_origin_household                  FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE manual_purchases                    ADD CONSTRAINT fk_manual_purchases_origin_household          FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE shopping_lists                       ADD CONSTRAINT fk_shopping_lists_origin_household            FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE consumption_snoozes                  ADD CONSTRAINT fk_consumption_snoozes_origin_household       FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE household_product_category_overrides ADD CONSTRAINT fk_hpco_origin_household                      FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE household_custom_categories          ADD CONSTRAINT fk_hcc_origin_household                       FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE household_product_aliases            ADD CONSTRAINT fk_hpa_origin_household                       FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE household_market_aliases             ADD CONSTRAINT fk_hma_origin_household                       FOREIGN KEY (origin_household_id) REFERENCES households(id);
ALTER TABLE manual_brand_preferences             ADD CONSTRAINT fk_mbp_origin_household                       FOREIGN KEY (origin_household_id) REFERENCES households(id);
