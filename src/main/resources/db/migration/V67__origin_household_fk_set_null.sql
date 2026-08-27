-- The V48 origin_household_id FKs had no ON DELETE action, so a household that
-- is the ORIGIN of rows now living in another household (post-merge) could never
-- be deleted — LGPD account deletion 500'd. Worse, the check is trigger-order
-- dependent: on a pg_restore'd database (prod) the NO ACTION check fires BEFORE
-- the household_id ON DELETE CASCADE removes the same row, breaking deletion
-- even for rows that never merged. Fix both by making origin references
-- order-independent: ON DELETE SET NULL. A null origin simply means "the origin
-- household no longer exists" — merge-split restore skips those rows.

ALTER TABLE receipts                             ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE manual_purchases                     ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE shopping_lists                       ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE consumption_snoozes                  ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE household_product_category_overrides ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE household_custom_categories          ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE household_product_aliases            ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE household_market_aliases             ALTER COLUMN origin_household_id DROP NOT NULL;
ALTER TABLE manual_brand_preferences             ALTER COLUMN origin_household_id DROP NOT NULL;

ALTER TABLE receipts                             DROP CONSTRAINT fk_receipts_origin_household;
ALTER TABLE manual_purchases                     DROP CONSTRAINT fk_manual_purchases_origin_household;
ALTER TABLE shopping_lists                       DROP CONSTRAINT fk_shopping_lists_origin_household;
ALTER TABLE consumption_snoozes                  DROP CONSTRAINT fk_consumption_snoozes_origin_household;
ALTER TABLE household_product_category_overrides DROP CONSTRAINT fk_hpco_origin_household;
ALTER TABLE household_custom_categories          DROP CONSTRAINT fk_hcc_origin_household;
ALTER TABLE household_product_aliases            DROP CONSTRAINT fk_hpa_origin_household;
ALTER TABLE household_market_aliases             DROP CONSTRAINT fk_hma_origin_household;
ALTER TABLE manual_brand_preferences             DROP CONSTRAINT fk_mbp_origin_household;

ALTER TABLE receipts                             ADD CONSTRAINT fk_receipts_origin_household            FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE manual_purchases                     ADD CONSTRAINT fk_manual_purchases_origin_household    FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE shopping_lists                       ADD CONSTRAINT fk_shopping_lists_origin_household      FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE consumption_snoozes                  ADD CONSTRAINT fk_consumption_snoozes_origin_household FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE household_product_category_overrides ADD CONSTRAINT fk_hpco_origin_household                FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE household_custom_categories          ADD CONSTRAINT fk_hcc_origin_household                 FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE household_product_aliases            ADD CONSTRAINT fk_hpa_origin_household                 FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE household_market_aliases             ADD CONSTRAINT fk_hma_origin_household                 FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE manual_brand_preferences             ADD CONSTRAINT fk_mbp_origin_household                 FOREIGN KEY (origin_household_id) REFERENCES households(id) ON DELETE SET NULL;
