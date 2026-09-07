-- One-off promo (2026-09-02): grant every existing user 6 months of PRO,
-- counted from the day this migration actually runs (now()). New signups get
-- the same rolling 6-month grant via SubscriptionService at registration time
-- (see economizai.subscription.promo.enabled) — this migration only backfills
-- accounts that already existed before the promo started.
INSERT INTO subscriptions (user_id, provider, provider_ref, status, current_period_end)
SELECT id, 'manual', NULL, 'ACTIVE', now() + INTERVAL '6 months'
FROM users
ON CONFLICT (user_id) DO UPDATE
    SET provider           = 'manual',
        provider_ref        = NULL,
        status              = 'ACTIVE',
        current_period_end  = now() + INTERVAL '6 months',
        updated_at          = now();

UPDATE users SET subscription_tier = 'PRO';
