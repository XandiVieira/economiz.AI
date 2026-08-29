ALTER TABLE products ADD COLUMN categorization_source VARCHAR(30) NOT NULL DEFAULT 'NONE';
CREATE INDEX idx_products_categorization_source ON products(categorization_source);
