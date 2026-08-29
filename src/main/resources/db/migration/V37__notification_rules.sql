-- Unified notification rule engine. Replaces the narrow price_alerts table:
-- every user-configurable trigger (price drop/ceiling, replenishment, budget)
-- and every toggleable system default (personal/community promo, cheaper
-- market, digest) is now a single notification_rules row the user can
-- create, enable, or disable.
--
-- A PRICE_DROP rule is the old "avise-me quando" price alert; /api/v1/alerts
-- stays as a backward-compatible alias over PRICE_DROP rules.
CREATE TABLE notification_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            VARCHAR(40) NOT NULL,
    product_id      UUID REFERENCES products(id),
    threshold_price NUMERIC(12,2),
    radius_km       DOUBLE PRECISION,
    lead_time_days  INTEGER,
    channel         VARCHAR(20),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    last_fired_at   TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, type, product_id)
);

-- Write-path lookup: "active product-scoped rules for these products".
CREATE INDEX idx_notification_rules_product_active ON notification_rules(product_id) WHERE active;
-- Scheduled-job lookup: "active rules of a given type" (replenishment, budget, digest).
CREATE INDEX idx_notification_rules_type_active ON notification_rules(type) WHERE active;
CREATE INDEX idx_notification_rules_user ON notification_rules(user_id);

-- Migrate existing price alerts into the unified table as PRICE_DROP rules.
INSERT INTO notification_rules
    (id, user_id, type, product_id, threshold_price, radius_km, active, is_default, last_fired_at, created_at, updated_at)
SELECT
    id, user_id, 'PRICE_DROP', product_id, threshold_price, radius_km, active, FALSE, last_fired_at, created_at, updated_at
FROM price_alerts;

DROP TABLE price_alerts;
