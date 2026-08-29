-- LLM teacher layer (enrichment + auditor) and photo-extracted receipts.

-- Enrichment bookkeeping on products (attempts capped like geocoding/segments).
ALTER TABLE products ADD COLUMN llm_enrichment_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN llm_enriched_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE products ADD COLUMN llm_audited_at TIMESTAMP WITH TIME ZONE;

-- Curated dictionary rules can now be authored by the LLM (admin can audit/purge by origin).
ALTER TABLE curated_dictionary_entries ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'ADMIN';

-- Quality signal: recorded whenever the LLM disagrees with a higher-ranked source
-- (or the auditor disputes a stored label). Reviewed by the admin; never auto-applied.
CREATE TABLE llm_disagreements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    field VARCHAR(20) NOT NULL,
    current_value VARCHAR(120),
    current_source VARCHAR(30),
    suggested_value VARCHAR(120),
    confidence NUMERIC(3,2),
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_disagreements_open ON llm_disagreements (created_at) WHERE resolved_at IS NULL;

-- Photo-extracted receipts: no SEFAZ chave/UF exists, so relax the columns and
-- track origin. Existing rows are all SCAN.
ALTER TABLE receipts ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'SCAN';
ALTER TABLE receipts ALTER COLUMN uf DROP NOT NULL;
