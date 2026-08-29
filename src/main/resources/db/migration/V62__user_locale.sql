-- Preferred UI language per user, captured from Accept-Language at registration.
-- Used for messages produced OFF the request thread (auth emails, push
-- notifications) where there's no request locale to read. Defaults to 'pt'
-- (the product's default locale).
ALTER TABLE users ADD COLUMN locale VARCHAR(5) NOT NULL DEFAULT 'pt';
