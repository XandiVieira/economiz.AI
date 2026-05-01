ALTER TABLE users ADD COLUMN accepted_terms_version VARCHAR(20);
ALTER TABLE users ADD COLUMN accepted_privacy_version VARCHAR(20);
ALTER TABLE users ADD COLUMN accepted_legal_at TIMESTAMP;

UPDATE users
SET accepted_terms_version = '1.0',
    accepted_privacy_version = '1.0',
    accepted_legal_at = created_at
WHERE accepted_terms_version IS NULL;

ALTER TABLE users ALTER COLUMN accepted_terms_version SET NOT NULL;
ALTER TABLE users ALTER COLUMN accepted_privacy_version SET NOT NULL;
ALTER TABLE users ALTER COLUMN accepted_legal_at SET NOT NULL;
