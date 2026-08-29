-- Push device token for mobile clients (FCM device token, set by FE
-- after the user grants notification permission and the app registers
-- with Firebase). Email + token are independent — a user might have
-- both, neither, or just one channel active.
ALTER TABLE users ADD COLUMN push_device_token VARCHAR(500);
ALTER TABLE users ADD COLUMN push_token_updated_at TIMESTAMP;

-- Per-user, per-event-type channel preference. Missing rows fall back
-- to the system default in NotificationService (see code).
CREATE TABLE notification_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, type)
);

CREATE INDEX idx_notification_preferences_user ON notification_preferences(user_id);

-- Outbox / delivery log. Every dispatch attempt (success or failure)
-- gets a row so we can audit what was sent and replay if needed.
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    title VARCHAR(255),
    body TEXT,
    payload TEXT,
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    delivered_at TIMESTAMP,
    failure_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_type ON notifications(type);
