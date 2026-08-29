-- Experimental multi-state rollout telemetry: one row per fallback-chain layer
-- attempt on a UF without a verified adapter. Powers /admin/state-coverage and
-- the once-a-day admin alert dedup (admin_notified on the EXHAUSTED row).
CREATE TABLE state_ingestion_attempts (
    id             UUID PRIMARY KEY,
    uf             VARCHAR(2)  NOT NULL,
    strategy       VARCHAR(20) NOT NULL,
    outcome        VARCHAR(20) NOT NULL,
    qr_host        VARCHAR(160),
    detail         TEXT,
    admin_notified BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_state_ingestion_attempts_uf_created ON state_ingestion_attempts (uf, created_at DESC);
