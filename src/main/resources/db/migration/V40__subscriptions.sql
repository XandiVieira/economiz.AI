-- PRO-tier billing record. One row per user (unique user_id). The provider /
-- provider_ref columns are the integration seam: a real payment provider
-- (Stripe / Mercado Pago) maps its subscription id into provider_ref and its
-- name into provider. status mirrors the provider lifecycle (ACTIVE / CANCELED
-- / EXPIRED); the user's subscription_tier on `users` is kept in sync by
-- SubscriptionService. Admins can also flip the tier directly for promos/ops.
CREATE TABLE subscriptions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    provider            VARCHAR(40),
    provider_ref        VARCHAR(255),
    status              VARCHAR(20) NOT NULL,
    current_period_end  TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_subscriptions_user ON subscriptions(user_id);
CREATE INDEX idx_subscriptions_status ON subscriptions(status);
