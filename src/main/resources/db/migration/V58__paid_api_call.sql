-- Cost-control ledger: one row per paid external call (Infosimples query, captcha
-- solve). Doubles as the reconciliation log (match against the provider invoice)
-- and the source for the per-user daily cap (count today's rows per service).
CREATE TABLE paid_api_call (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID REFERENCES users (id) ON DELETE SET NULL,
    service              VARCHAR(32) NOT NULL,
    uf                   VARCHAR(2),
    provider             VARCHAR(32),
    success              BOOLEAN NOT NULL,
    estimated_cost_cents INTEGER NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Serves the daily-cap count query: rows for (user, service) since start of day.
CREATE INDEX idx_paid_api_call_user_service_time
    ON paid_api_call (user_id, service, created_at);
