-- "Read" semantics for the user-facing notification inbox: nullable
-- timestamp so the FE can show unread counts and per-row read state
-- without a separate join. Indexed for the typical query
-- (unread-count for current user).
ALTER TABLE notifications ADD COLUMN read_at TIMESTAMP;
CREATE INDEX idx_notifications_user_read_at ON notifications(user_id, read_at);
