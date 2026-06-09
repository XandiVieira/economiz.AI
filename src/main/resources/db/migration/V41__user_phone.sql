-- User phone number (E.164) + verification flag, plus a short-lived OTP table.
-- The OTP is stored HASHED (BCrypt) with a short TTL, mirroring the per-purpose
-- token-table pattern used for password_reset_tokens / email_verification_tokens.
-- A separate table (not auth_tokens reuse) keeps the 6-digit numeric OTP, its
-- distinct short TTL, and its hashed-at-rest storage isolated from the URL-safe
-- auth-link tokens.

ALTER TABLE users ADD COLUMN phone_number VARCHAR(20);
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE phone_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    phone_number VARCHAR(20) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_phone_verification_tokens_user ON phone_verification_tokens(user_id);
