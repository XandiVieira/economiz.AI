-- Phase C — daily deals digest: per-user delivery preferences + 1/day cap marker.
ALTER TABLE users
    ADD COLUMN digest_frequency VARCHAR(10) NOT NULL DEFAULT 'DAILY',
    ADD COLUMN digest_send_hour INTEGER,
    ADD COLUMN last_digest_sent_at TIMESTAMP WITH TIME ZONE;

-- send-hour override is a wall-clock hour-of-day
ALTER TABLE users
    ADD CONSTRAINT chk_users_digest_send_hour
        CHECK (digest_send_hour IS NULL OR (digest_send_hour BETWEEN 0 AND 23));
