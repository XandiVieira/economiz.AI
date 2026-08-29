ALTER TABLE receipts DROP CONSTRAINT IF EXISTS receipts_user_id_fkey;
ALTER TABLE receipts ADD CONSTRAINT receipts_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
