-- Test-only migration (M1-V2 / M1-V5). Applied by M1ProbeSchemaConfiguration with its own Flyway
-- history table in schema m1_probe, so it never mixes with the application schema (public).
CREATE SCHEMA IF NOT EXISTS m1_probe;

CREATE TABLE m1_probe.jsonb_probe (
    id UUID PRIMARY KEY,
    payload JSONB NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);
