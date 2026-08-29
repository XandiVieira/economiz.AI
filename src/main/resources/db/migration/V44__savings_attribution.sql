-- Phase D — closes the notifications telemetry loop: attribute realized R$
-- savings to deals we surfaced. When a user buys a product at a market we
-- surfaced as a deal (within the attribution window), we record a CONVERTED
-- event and the realized savings, then stamp the surface row so a single
-- surfacing is attributed at most once (a fresh re-surface later resets it).

-- Attribution-window guard: nullable so an un-attributed surfacing is found by
-- "converted_at IS NULL"; set to now() when we attribute a conversion to it.
ALTER TABLE deal_surface_state
    ADD COLUMN converted_at TIMESTAMP WITH TIME ZONE;

-- Realized savings (R$) carried directly on the CONVERTED telemetry row so the
-- north-star ("R$ saved attributable to notifications") sums in plain SQL
-- instead of parsing the JSON metadata blob. Null for non-CONVERTED events.
ALTER TABLE notification_events
    ADD COLUMN savings_amount NUMERIC(12,2);
