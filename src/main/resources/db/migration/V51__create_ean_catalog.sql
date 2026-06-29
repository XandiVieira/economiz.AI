-- EAN catalog: maps product barcodes to categories.
-- Seeded from Open Food Facts (source=OPEN_FOOD_FACTS) or admin imports.
-- Queried in the canonicalization cascade BEFORE the keyword dictionary —
-- a real GTIN match here means we already know the category.
CREATE TABLE ean_catalog (
    id          UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
    ean         VARCHAR(14)              NOT NULL UNIQUE,
    generic_name VARCHAR(100),
    brand       VARCHAR(100),
    category    VARCHAR(30),
    source      VARCHAR(30)              NOT NULL DEFAULT 'OPEN_FOOD_FACTS',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_ean_catalog_ean ON ean_catalog (ean);
