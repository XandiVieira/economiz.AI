-- "Avise-me quando" price-drop alerts. A user pins a product + a threshold
-- price (optionally bounded to a radius from home). When any household's
-- confirmed receipt contributes an observation at/under the threshold, the
-- alert owner is notified — the community retention loop.
--
-- One rule per (user, product). Cascade on user delete. product_id is a hard
-- FK (canonical products don't disappear). last_fired_at drives an anti-spam
-- cooldown enforced in the service layer.
CREATE TABLE price_alerts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id      UUID NOT NULL REFERENCES products(id),
    threshold_price NUMERIC(12,2) NOT NULL,
    radius_km       DOUBLE PRECISION,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    last_fired_at   TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, product_id)
);

-- Write-path lookup: "active alerts for these products". Partial index keeps
-- it tight since only active rules are ever queried for evaluation.
CREATE INDEX idx_price_alerts_product_active ON price_alerts(product_id) WHERE active;
CREATE INDEX idx_price_alerts_user ON price_alerts(user_id);
