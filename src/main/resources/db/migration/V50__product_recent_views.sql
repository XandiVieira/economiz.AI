-- Tracks the last time each user opened a product's detail screen.
-- Unique on (user_id, product_id) so repeated views just update viewed_at.
CREATE TABLE product_recent_views (
    id          UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID                     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id  UUID                     NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    viewed_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_recent_view UNIQUE (user_id, product_id)
);

CREATE INDEX idx_product_recent_views_user_viewed
    ON product_recent_views(user_id, viewed_at DESC);
