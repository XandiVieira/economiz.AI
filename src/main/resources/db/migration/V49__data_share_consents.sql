-- Cross-person data-share consent (Phase 2). When a member leaves and wants to take
-- (or a stayer wants to keep) data ANOTHER person scanned, that person must approve.
-- One row per request: who's asking, whose data, the scope, and the lifecycle status.
CREATE TABLE data_share_consents (
    id                  UUID PRIMARY KEY,
    requester_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grantor_user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- the household the data currently lives in (the shared household at request time)
    household_id        UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    -- where approved data should be copied to (the requester's destination household)
    destination_household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    scope               VARCHAR(30) NOT NULL,   -- LeaveScope (BOTH, etc.)
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMP WITH TIME ZONE,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_data_share_consents_grantor_status ON data_share_consents(grantor_user_id, status);
CREATE INDEX idx_data_share_consents_requester ON data_share_consents(requester_user_id);
