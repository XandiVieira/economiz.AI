-- Receipt-level discount ("Descontos R$") as printed on the NFC-e.
-- We no longer distribute this across item prices (that distorted the
-- per-item price index); item prices stay gross-as-printed and the
-- receipt-level discount is tracked here for later use (e.g. "markets
-- with the biggest discounts"). Null when the receipt declared no discount.
ALTER TABLE receipts ADD COLUMN discount_total NUMERIC(12,2);
