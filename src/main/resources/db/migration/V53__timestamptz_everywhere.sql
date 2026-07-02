-- Convention (CLAUDE.md): timestamps are TIMESTAMP WITH TIME ZONE. Tables
-- created before V35 used plain TIMESTAMP; with app and DB both on UTC the
-- values are correct, so converting them AS UTC is lossless. Converting now
-- removes the silent skew risk if either side's timezone ever changes.
-- Dynamic loop so every legacy column is caught (~45 across V1-V34 tables);
-- Flyway's own history table is excluded.
DO $$
DECLARE
    legacy_column RECORD;
BEGIN
    FOR legacy_column IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND data_type = 'timestamp without time zone'
          AND table_name <> 'flyway_schema_history'
    LOOP
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN %I TYPE timestamptz USING %I AT TIME ZONE ''UTC''',
            legacy_column.table_name, legacy_column.column_name, legacy_column.column_name);
    END LOOP;
END $$;
