-- PRO-46: invite codes expire 48h after generation so a leaked code can't
-- be used indefinitely. Existing households get NULL → join check treats
-- NULL as "never expires" so we don't break anyone mid-flight.
ALTER TABLE households ADD COLUMN invite_code_expires_at TIMESTAMP;
