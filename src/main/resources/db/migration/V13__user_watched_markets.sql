-- A user-curated list of markets to monitor regardless of distance from home.
-- Use case: I commute past Mercado X every day, want promos there even though
-- it's outside my home radius. Combines with radius filter as OR (radius OR watched).
--
-- One row per (user, market_cnpj). Cascade on user delete; market_cnpj is a
-- soft reference (no FK) since market_locations is a cache and rows there
-- come and go independently of receipts. We keep the CNPJ as the source of truth.
CREATE TABLE user_watched_markets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    market_cnpj VARCHAR(14) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, market_cnpj)
);

CREATE INDEX idx_user_watched_markets_user ON user_watched_markets(user_id);
CREATE INDEX idx_user_watched_markets_cnpj ON user_watched_markets(market_cnpj);
