CREATE TABLE learned_dictionary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    normalized_token VARCHAR(100) NOT NULL UNIQUE,
    generic_name VARCHAR(100),
    category VARCHAR(30) NOT NULL,
    sample_count INTEGER NOT NULL,
    promoted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_learned_dictionary_token ON learned_dictionary(normalized_token);
CREATE INDEX idx_learned_dictionary_category ON learned_dictionary(category);
