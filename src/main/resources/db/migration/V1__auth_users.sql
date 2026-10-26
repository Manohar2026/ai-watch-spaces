-- V1 (M2): identity and authentication — app_users, refresh_tokens.
-- Copied verbatim from DATABASE_SCHEMA.sql (v0.4, target state); V1MigrationMatchesTargetSchemaIT compares the two.


CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,              -- DelegatingPasswordEncoder format, e.g. "{bcrypt}$2a$12$..."
    display_name VARCHAR(80) NOT NULL,
    system_role VARCHAR(16) NOT NULL DEFAULT 'USER'
        CHECK (system_role IN ('USER', 'ADMIN')),     -- global role only; HOST/VIEWER are room-scoped
    subtitle_locale VARCHAR(16) NOT NULL DEFAULT 'en', -- BCP 47 tag, validated by the application
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Opaque refresh tokens. Only SHA-256 hex hashes are stored.
-- Rotation: every successful refresh revokes the presented token (ROTATED), links it to its
-- replacement, and issues a new token in the same family. Presenting an already revoked
-- token is a replay: the whole family is revoked (REUSE_DETECTED) and the request fails.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(24)
        CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED')),
    replaced_by_token_id UUID REFERENCES refresh_tokens(id) ON DELETE RESTRICT,
    CHECK (expires_at > created_at),
    CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CHECK (replaced_by_token_id IS NULL OR revoked_reason = 'ROTATED')
);
CREATE INDEX ix_refresh_tokens_user_expiry ON refresh_tokens(user_id, expires_at);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens(family_id);


