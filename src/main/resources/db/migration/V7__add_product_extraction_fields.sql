ALTER TABLE products ADD COLUMN generic_name VARCHAR(100);
ALTER TABLE products ADD COLUMN pack_size NUMERIC(10,3);
ALTER TABLE products ADD COLUMN pack_unit VARCHAR(10);

CREATE INDEX idx_products_generic_name ON products(generic_name);
