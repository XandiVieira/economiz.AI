-- NFC-e items can carry a discount / promo marker (e.g. "OFERTA",
-- "PROMOCAO", or a discount column on the SEFAZ HTML). When present, the
-- price the user paid is a one-off and must NOT be used as a baseline
-- when detecting community promos — otherwise we'd compare promos to
-- promos and never flag anything.
--
-- Two columns:
--   receipt_items.nfce_promo_flag — what the user sees in their history
--   price_observations.promo_flag — what the panel queries filter on
-- Same value, propagated at write time. Both default false (vast
-- majority of items aren't promo-flagged).

ALTER TABLE receipt_items ADD COLUMN nfce_promo_flag BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE price_observations ADD COLUMN promo_flag BOOLEAN NOT NULL DEFAULT FALSE;
