-- Merchant support gate: raw CNAE codes for grey-zone review, admin override
-- (SUPPORTED/BLOCKED) and the one-shot grey-sighting admin alert flag.
ALTER TABLE market_locations ADD COLUMN cnae_codes TEXT;
ALTER TABLE market_locations ADD COLUMN support_override VARCHAR(20);
ALTER TABLE market_locations ADD COLUMN gray_sighting_notified_at TIMESTAMP WITH TIME ZONE;
