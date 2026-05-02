-- PRO-51 acceptance criteria need two new pieces of state:
--
--   (a) "Não preciso agora" — user dismisses a suggested-list item without
--       buying it. Suppress the suggestion until snoozed_until passes.
--   (b) "Já comprei" — user logs a purchase they made without scanning the
--       receipt. Counts toward consumption-cadence intervals just like a
--       confirmed-receipt item, but doesn't pollute the price index (no
--       price/market — that requires a real receipt).

CREATE TABLE consumption_snoozes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    snoozed_until TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (household_id, product_id)
);
CREATE INDEX idx_consumption_snoozes_household ON consumption_snoozes(household_id);

CREATE TABLE manual_purchases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity NUMERIC(12,3) NOT NULL DEFAULT 1,
    purchased_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_manual_purchases_household_product ON manual_purchases(household_id, product_id);
