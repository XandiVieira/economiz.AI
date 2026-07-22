-- Track the platform a user authenticates from (web / android / ios). The FE
-- sends it (optionally) on every login/register. We keep the ORIGINAL platform
-- (the one used at registration), the LAST platform used, and the last login
-- time per platform. All nullable: existing users and clients that don't send
-- the field yet simply carry no platform data.
ALTER TABLE users ADD COLUMN registration_platform VARCHAR(10);
ALTER TABLE users ADD COLUMN last_platform          VARCHAR(10);
ALTER TABLE users ADD COLUMN last_web_login_at      TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN last_android_login_at  TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN last_ios_login_at      TIMESTAMP WITH TIME ZONE;
