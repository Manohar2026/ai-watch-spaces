-- Test-only migration (M1-V2 / M1-V5). Lives in its own schema so it never mixes with the
-- application schema defined by DATABASE_SCHEMA.sql.
CREATE SCHEMA m1_probe;

CREATE TABLE m1_probe.jsonb_probe (
    id UUID PRIMARY KEY,
    payload JSONB NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);
