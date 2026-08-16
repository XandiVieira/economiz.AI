-- Manual per-item discount ("preço pago") captured by the user in the review
-- screen. The NFC-e only ever carries a single receipt-level discount
-- (receipts.discount_total), never a per-line amount, so when a user buys an
-- item on promotion they can record what they actually paid for that line here.
--
-- The original unit_price/total_price stay as-printed (shelf price) and remain
-- the collaborative price-index baseline; these columns hold what was really
-- paid. Null when the line has no manual discount. Index behaviour is
-- intentionally left unchanged for now (both prices are stored; how the paid
-- price feeds aggregates is a later decision).
ALTER TABLE receipt_items ADD COLUMN paid_unit_price NUMERIC(12,4);
ALTER TABLE receipt_items ADD COLUMN paid_total_price NUMERIC(12,2);
