-- Approximate tax burden disclosed on every NFC-e under Lei 12.741/2012.
-- The "Trib aprox R$ X Federal, R$ Y Estadual Fonte: IBPT" line is an
-- IBPT-table estimate of the federal + estadual taxes embedded in the
-- retail prices (ICMS, IPI, PIS, COFINS, IOF, …) — NOT taxes the consumer
-- paid separately, NOT what the merchant actually remitted. Surfaced as
-- "imposto aproximado" so users can see the tax burden on their groceries.
--
-- Both columns nullable: the law mandates disclosure but in practice some
-- merchants leave the line blank or declare R$ 0,00. Aggregations must
-- filter out NULL receipts so the percentage isn't diluted by missing data.
ALTER TABLE receipts ADD COLUMN approx_tax_federal NUMERIC(12,2);
ALTER TABLE receipts ADD COLUMN approx_tax_estadual NUMERIC(12,2);
