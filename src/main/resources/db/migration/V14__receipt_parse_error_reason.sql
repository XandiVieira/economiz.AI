-- PRO-43: SefazAdapter sometimes fails to parse the SVRS HTML (XSLT version
-- drift, fields missing). We persist the receipt with status FAILED_PARSE +
-- raw HTML + a short reason so ops can grep failures without re-fetching.
ALTER TABLE receipts ADD COLUMN parse_error_reason TEXT;
