-- Brute-force guard: count failed verify attempts against the active reset
-- code and lock it after a small budget (enforced in PasswordResetService).
ALTER TABLE password_reset_tokens
    ADD COLUMN attempts INT NOT NULL DEFAULT 0;
