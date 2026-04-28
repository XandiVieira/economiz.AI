CREATE TABLE receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    household_id UUID NOT NULL REFERENCES households(id),
    chave_acesso VARCHAR(44) NOT NULL UNIQUE,
    uf VARCHAR(2) NOT NULL,
    cnpj_emitente VARCHAR(14),
    market_name VARCHAR(255),
    market_address VARCHAR(500),
    issued_at TIMESTAMP,
    total_amount NUMERIC(12,2),
    qr_payload TEXT NOT NULL,
    source_url TEXT,
    raw_html TEXT,
    status VARCHAR(30) NOT NULL,
    confirmed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_receipts_user_id ON receipts(user_id);
CREATE INDEX idx_receipts_household_id ON receipts(household_id);
CREATE INDEX idx_receipts_status ON receipts(status);
CREATE INDEX idx_receipts_issued_at ON receipts(issued_at DESC);

CREATE TABLE receipt_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_id UUID NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
    line_number INTEGER NOT NULL,
    raw_description TEXT NOT NULL,
    ean VARCHAR(14),
    quantity NUMERIC(12,3) NOT NULL,
    unit VARCHAR(10),
    unit_price NUMERIC(12,4),
    total_price NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_receipt_items_receipt_id ON receipt_items(receipt_id);
