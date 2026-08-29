-- Business segment of a market, verified from its CNPJ's CNAE (external lookup).
-- UNKNOWN until the async classifier resolves it; SUPERMARKET / PHARMACY / OTHER after.
ALTER TABLE market_locations ADD COLUMN segment VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE market_locations ADD COLUMN segment_classified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE market_locations ADD COLUMN segment_attempts INTEGER NOT NULL DEFAULT 0;
