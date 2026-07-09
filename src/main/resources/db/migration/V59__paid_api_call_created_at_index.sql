-- The global kill-switch sums estimated_cost_cents across ALL users for today on
-- every paid call, and the cost report scans by time window. Index created_at so
-- neither degrades into a full-table scan as the ledger grows.
CREATE INDEX idx_paid_api_call_created_at ON paid_api_call (created_at);
