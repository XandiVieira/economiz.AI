CREATE TABLE households (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invite_code VARCHAR(8) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_households_invite_code ON households(invite_code);

ALTER TABLE users ADD COLUMN household_id UUID;

DO $$
DECLARE
    user_record RECORD;
    new_household_id UUID;
BEGIN
    FOR user_record IN SELECT id FROM users LOOP
        INSERT INTO households (invite_code)
            VALUES (upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 6)))
            RETURNING id INTO new_household_id;
        UPDATE users SET household_id = new_household_id WHERE id = user_record.id;
    END LOOP;
END $$;

ALTER TABLE users ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT fk_users_household FOREIGN KEY (household_id) REFERENCES households(id);
CREATE INDEX idx_users_household_id ON users(household_id);
