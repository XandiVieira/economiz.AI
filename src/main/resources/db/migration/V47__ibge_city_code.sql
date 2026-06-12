-- Official 7-digit IBGE municipality code, the stable key for regional
-- aggregation (free-text city names vary in casing/spelling and break
-- GROUP BY). Populated on market_locations by the merchant-classification
-- job (BrasilAPI CNPJ lookup returns codigo_municipio_ibge) and snapshotted
-- onto each price_observation at write time, like city/state.
ALTER TABLE market_locations ADD COLUMN ibge_city_code VARCHAR(7);
ALTER TABLE price_observations ADD COLUMN ibge_city_code VARCHAR(7);
