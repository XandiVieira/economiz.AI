-- Originally chave_acesso was globally unique on receipts: once any user
-- submitted a NFC-e, nobody else could (409). That blocks legitimate
-- cross-household scenarios (couple split the bill, both want the receipt
-- in their own history) AND makes FE testing painful (every test run
-- needed a brand-new chave).
--
-- New rule: chave_acesso is unique PER HOUSEHOLD. A household still
-- can't double-import its own receipts, but two different households can
-- both record the same fiscal event. Slight bias on the price-index side
-- (one fiscal event becomes 2+ PriceObservations) — acceptable for V1
-- and rare in practice.

ALTER TABLE receipts DROP CONSTRAINT IF EXISTS receipts_chave_acesso_key;
ALTER TABLE receipts ADD CONSTRAINT receipts_household_chave_unique
    UNIQUE (household_id, chave_acesso);

CREATE INDEX IF NOT EXISTS idx_receipts_chave_acesso ON receipts(chave_acesso);
