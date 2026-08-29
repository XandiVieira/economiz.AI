-- Cached lat/lng per market (CNPJ unique). Populated lazily by
-- GeocodingService — never blocks the receipt-confirm critical path.
-- Address is captured from the first receipt that mentions this CNPJ
-- and used as the geocoder query.
CREATE TABLE market_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cnpj VARCHAR(14) NOT NULL UNIQUE,
    cnpj_root VARCHAR(8) NOT NULL,
    name VARCHAR(255),
    address TEXT,
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    geocoded_at TIMESTAMP,
    geocode_failed_at TIMESTAMP,
    geocode_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_market_locations_cnpj_root ON market_locations(cnpj_root);
CREATE INDEX idx_market_locations_geocoded_at ON market_locations(geocoded_at);

-- User's home reference point. Set explicitly by FE (browser geolocation
-- API, mobile geolocation, or by typing CEP and resolving). Used to
-- filter best-markets / promos by radius.
ALTER TABLE users ADD COLUMN home_latitude NUMERIC(10,7);
ALTER TABLE users ADD COLUMN home_longitude NUMERIC(10,7);
ALTER TABLE users ADD COLUMN home_set_at TIMESTAMP;
