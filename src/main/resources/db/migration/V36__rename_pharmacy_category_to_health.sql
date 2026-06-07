-- ProductCategory.PHARMACY was renamed to HEALTH (broader: meds + supplements
-- + first-aid; the store type stays MerchantSegment.PHARMACY). Category is stored
-- as the enum name (@Enumerated STRING), so convert existing rows or they'd fail
-- to deserialize.
UPDATE products SET category = 'HEALTH' WHERE category = 'PHARMACY';
UPDATE household_product_category_overrides SET category = 'HEALTH' WHERE category = 'PHARMACY';
