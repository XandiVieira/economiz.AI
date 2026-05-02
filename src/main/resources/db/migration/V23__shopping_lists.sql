-- Persistent shopping lists per household. The existing
-- POST /shopping-list/optimize stays as the stateless one-shot helper;
-- these tables back the FE workflow of build-edit-shop.

CREATE TABLE shopping_lists (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_shopping_lists_household ON shopping_lists(household_id);

CREATE TABLE shopping_list_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shopping_list_id UUID NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE,
    -- Either bound to a canonical Product (auto-suggestions, optimizer-friendly)
    -- OR a free-text "I want to buy salt" entry (user-typed). product_id is
    -- nullable for the free-text case.
    product_id UUID REFERENCES products(id) ON DELETE SET NULL,
    free_text VARCHAR(255),
    quantity NUMERIC(12,3) NOT NULL DEFAULT 1,
    position INTEGER NOT NULL DEFAULT 0,
    checked BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_shopping_list_items_list ON shopping_list_items(shopping_list_id);
