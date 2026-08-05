-- LlmDisagreement extends BaseEntity, which requires updated_at on every row;
-- V64 never added it, so every select against llm_disagreements fails.
ALTER TABLE llm_disagreements ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
