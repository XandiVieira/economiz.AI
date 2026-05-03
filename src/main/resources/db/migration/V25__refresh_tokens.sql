-- Long-lived refresh tokens for the access-token rotation flow.
-- Same shape as the other auth tokens (V24) but with a different TTL
-- (30 days vs. 1h) and a `revoked_at` column for explicit logout — a
-- consumed token is one that was rotated; a revoked token is one the
-- user (or a security flow) invalidated.

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
