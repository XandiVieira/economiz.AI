-- PRO-53/54: regional grouping. Capture city + state on each MarketLocation
-- (populated by the Nominatim geocoder) and snapshot to PriceObservation at
-- write time so retroactive geocode changes don't rewrite history.
--
-- IBGE municipality code is intentionally NOT added here — it requires a
-- city-name → IBGE-code lookup table we haven't loaded. City + state are
-- enough for "all markets in Porto Alegre/RS" queries; IBGE code can be
-- backfilled later as a denormalised join when we ship the lookup table.
ALTER TABLE market_locations ADD COLUMN city VARCHAR(120);
ALTER TABLE market_locations ADD COLUMN state VARCHAR(2);
ALTER TABLE price_observations ADD COLUMN city VARCHAR(120);
ALTER TABLE price_observations ADD COLUMN state VARCHAR(2);
CREATE INDEX idx_price_observations_city_state ON price_observations(city, state);
