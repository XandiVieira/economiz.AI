-- Email verification moves from a 32-byte link token to a 6-digit code typed
-- in the app (same UX as password reset): hashed at rest + attempt counter.
ALTER TABLE email_verification_tokens
    ADD COLUMN attempts INT NOT NULL DEFAULT 0;

-- Hashed short codes are NOT unique across users (two users can be issued the
-- same 6-digit code → identical hash), so the UNIQUE constraints from V24 must
-- go on both token tables. Lookups are user-scoped, never by bare token.
ALTER TABLE email_verification_tokens DROP CONSTRAINT IF EXISTS email_verification_tokens_token_key;
ALTER TABLE password_reset_tokens DROP CONSTRAINT IF EXISTS password_reset_tokens_token_key;
