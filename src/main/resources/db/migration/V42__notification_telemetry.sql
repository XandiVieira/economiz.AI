-- Telemetry foundation for the learning-ready notification system (Phase A).
-- Every user-feedback signal lands here so a future ranking/learning engine
-- has training data. Nothing consumes these rows yet — this is the event log.
CREATE TABLE notification_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Nullable: client-reported events (e.g. SCREEN_OPENED) may have no
    -- originating push. SET NULL keeps the signal even if the push is purged.
    notification_id UUID REFERENCES notifications(id) ON DELETE SET NULL,
    event_type VARCHAR(30) NOT NULL,
    product_id UUID REFERENCES products(id) ON DELETE SET NULL,
    market_cnpj VARCHAR(14),
    channel VARCHAR(20),
    -- JSON as TEXT (not jsonb) to stay H2-test-portable; migrate to jsonb
    -- later if we ever need to query inside it.
    metadata TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_events_user_occurred ON notification_events(user_id, occurred_at);
CREATE INDEX idx_notification_events_type ON notification_events(event_type);
CREATE INDEX idx_notification_events_product ON notification_events(product_id);

-- Dedup-by-state-change: a future digest reads/writes this so it only re-surfaces
-- a deal when the discount/price meaningfully changes. Created now, unused yet.
CREATE TABLE deal_surface_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    market_cnpj VARCHAR(14) NOT NULL,
    last_discount_fraction NUMERIC(6,4),
    last_unit_price NUMERIC(12,2),
    last_surfaced_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (user_id, product_id, market_cnpj)
);
