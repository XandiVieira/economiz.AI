-- Social sign-in (Google / Apple). The mobile app obtains the provider's
-- ID/identity token natively; the backend verifies it and issues OUR JWT.
-- Social users have no password, so password becomes nullable. LOCAL users
-- keep a password and provider_subject = NULL.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

ALTER TABLE users ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE users ADD COLUMN provider_subject VARCHAR(255);

-- One account per (provider, subject). NULL provider_subject (all LOCAL users)
-- never collides — Postgres treats NULLs as distinct in a UNIQUE constraint.
ALTER TABLE users ADD CONSTRAINT uq_users_auth_provider_subject UNIQUE (auth_provider, provider_subject);
