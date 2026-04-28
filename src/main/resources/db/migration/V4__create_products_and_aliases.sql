CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ean VARCHAR(14) UNIQUE,
    normalized_name VARCHAR(255) NOT NULL,
    brand VARCHAR(100),
    category VARCHAR(30),
    unit VARCHAR(10),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_ean ON products(ean);
CREATE INDEX idx_products_normalized_name ON products(normalized_name);
CREATE INDEX idx_products_category ON products(category);

CREATE TABLE product_aliases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    raw_description VARCHAR(500) NOT NULL,
    normalized_description VARCHAR(500) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_product_aliases_product_id ON product_aliases(product_id);

ALTER TABLE receipt_items ADD COLUMN product_id UUID REFERENCES products(id) ON DELETE SET NULL;
CREATE INDEX idx_receipt_items_product_id ON receipt_items(product_id);
