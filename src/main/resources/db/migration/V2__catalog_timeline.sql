-- V2 (M3): catalogue, timeline metadata, subtitles and approved variation options.
-- Exact V2 slice from DATABASE_SCHEMA.sql v0.4. Do not edit this migration independently of the canonical schema.

-- Catalogue (module: catalog)
-- -------------------------------------------------------------------------------------

CREATE TABLE titles (
    id UUID PRIMARY KEY,
    slug VARCHAR(160) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    duration_ms INTEGER NOT NULL CHECK (duration_ms > 0),
    video_asset_uri TEXT NOT NULL,
    default_locale VARCHAR(16) NOT NULL DEFAULT 'en',  -- base subtitle locale for cue-key validation and fallback
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_by UUID REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_titles_status_name ON titles(status, name);

CREATE TABLE genres (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE                   -- stored normalized (trimmed, lower-case)
);

CREATE TABLE title_genres (
    title_id UUID NOT NULL REFERENCES titles(id) ON DELETE RESTRICT,
    genre_id UUID NOT NULL REFERENCES genres(id) ON DELETE RESTRICT,
    PRIMARY KEY (title_id, genre_id)
);
CREATE INDEX ix_title_genres_genre ON title_genres(genre_id, title_id);


-- -------------------------------------------------------------------------------------
-- Timeline metadata (module: timeline)
-- -------------------------------------------------------------------------------------

-- Lifecycle (status):            DRAFT -> CURRENT -> SUPERSEDED
-- Validation (validation_status): PENDING -> VALID | INVALID   (re-validation allowed while DRAFT)
-- timeline_events / variation_options rows are materialized ONLY for VALID versions, and a
-- version's events are immutable once materialized (retrieval caches rely on this).
CREATE TABLE title_timeline_versions (
    id UUID PRIMARY KEY,
    title_id UUID NOT NULL REFERENCES titles(id) ON DELETE RESTRICT,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'CURRENT', 'SUPERSEDED')),
    source_format VARCHAR(8) NOT NULL CHECK (source_format IN ('JSON', 'YAML')),
    raw_document TEXT NOT NULL CHECK (octet_length(raw_document) <= 2097152),  -- 2 MiB upload limit
    schema_version INTEGER,                            -- NULL only if the document could not be parsed
    content_hash CHAR(64) NOT NULL,                    -- SHA-256 of raw_document
    validation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (validation_status IN ('PENDING', 'VALID', 'INVALID')),
    validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,   -- [{path, code, message}]
    validated_at TIMESTAMPTZ,
    uploaded_by UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE (title_id, version_number),
    UNIQUE (title_id, id),
    CHECK ((validation_status = 'PENDING') = (validated_at IS NULL)),
    CHECK (validation_status <> 'INVALID' OR jsonb_array_length(validation_errors) > 0),
    CHECK (validation_status <> 'VALID' OR jsonb_array_length(validation_errors) = 0),
    CHECK (status = 'DRAFT' OR validation_status = 'VALID'),
    CHECK ((status = 'DRAFT') = (published_at IS NULL))
);
CREATE UNIQUE INDEX ux_timeline_one_current_per_title
    ON title_timeline_versions(title_id) WHERE status = 'CURRENT';

CREATE TABLE timeline_events (
    id UUID PRIMARY KEY,
    timeline_version_id UUID NOT NULL REFERENCES title_timeline_versions(id) ON DELETE RESTRICT,
    event_key VARCHAR(160) NOT NULL,                   -- stable author key; distinct from the DB id
    event_type VARCHAR(32) NOT NULL
        CHECK (event_type IN ('SCENE', 'CHARACTER', 'TRIVIA', 'GLOSSARY', 'VARIATION_POINT')),
    start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms INTEGER,                                    -- NULL = point event
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,        -- type-specific fields, plain text only
    search_text TEXT NOT NULL DEFAULT '',              -- normalized text used for keyword retrieval
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_ms IS NULL OR end_ms >= start_ms),
    UNIQUE (timeline_version_id, event_key),
    UNIQUE (timeline_version_id, id)
);
-- start/end inside title duration is validated by the application (cross-table rule).
CREATE INDEX ix_timeline_events_version_start
    ON timeline_events(timeline_version_id, start_ms);
CREATE INDEX ix_timeline_events_version_type_start
    ON timeline_events(timeline_version_id, event_type, start_ms);


-- -------------------------------------------------------------------------------------
-- Subtitles (module: variations). MVP: tracks and cues are seeded, not uploaded via API.
-- -------------------------------------------------------------------------------------

CREATE TABLE subtitle_tracks (
    id UUID PRIMARY KEY,
    title_id UUID NOT NULL REFERENCES titles(id) ON DELETE RESTRICT,
    locale VARCHAR(16) NOT NULL,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    asset_uri TEXT NOT NULL,
    checksum CHAR(64),                                 -- SHA-256 of the asset file
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'APPROVED', 'ACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (title_id, locale, version_number),
    UNIQUE (id, title_id, locale),                     -- composite FK target for room pinning
    UNIQUE (id, locale)                                -- composite FK target for SUBTITLE_TRACK_SWAP options (v0.4)
);
CREATE UNIQUE INDEX ux_subtitle_one_active_per_locale
    ON subtitle_tracks(title_id, locale) WHERE status = 'ACTIVE';

-- cue_key is stable across track versions and locales of the same title; variation points
-- reference cues by cue_key (payload.cueKey), never by a track-specific cue id.
CREATE TABLE subtitle_cues (
    id UUID PRIMARY KEY,
    subtitle_track_id UUID NOT NULL REFERENCES subtitle_tracks(id) ON DELETE RESTRICT,
    cue_key VARCHAR(160) NOT NULL,
    start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms INTEGER NOT NULL CHECK (end_ms > start_ms),
    text_value TEXT NOT NULL,
    UNIQUE (subtitle_track_id, cue_key)
);
CREATE INDEX ix_subtitle_cues_track_start ON subtitle_cues(subtitle_track_id, start_ms);


-- -------------------------------------------------------------------------------------
-- Pre-authored, pre-approved variation options (modules: timeline writes, variations reads)
-- -------------------------------------------------------------------------------------

CREATE TABLE variation_options (
    id UUID PRIMARY KEY,
    timeline_event_id UUID NOT NULL REFERENCES timeline_events(id) ON DELETE RESTRICT,
    option_key VARCHAR(100) NOT NULL,
    label VARCHAR(200) NOT NULL,
    option_type VARCHAR(32) NOT NULL
        CHECK (option_type IN ('SUBTITLE_TEXT', 'SUBTITLE_TRACK_SWAP', 'ASSET_SWAP', 'NARRATIVE_BRANCH')),
    locale VARCHAR(16),
    text_value TEXT,
    asset_uri TEXT,
    subtitle_track_id UUID,                            -- only for SUBTITLE_TRACK_SWAP; FK below
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_approved BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    UNIQUE (timeline_event_id, option_key),
    UNIQUE (timeline_event_id, id),                    -- composite FK target (votes, ballots, applications)
    CHECK (option_type <> 'SUBTITLE_TEXT'       OR (text_value IS NOT NULL AND locale IS NOT NULL)),
    CHECK (option_type <> 'SUBTITLE_TRACK_SWAP' OR (subtitle_track_id IS NOT NULL AND locale IS NOT NULL)),
    -- A swap option's locale must equal its track's locale (v0.4). Same-title rule: R11.
    FOREIGN KEY (subtitle_track_id, locale) REFERENCES subtitle_tracks(id, locale) ON DELETE RESTRICT,
    CHECK (option_type NOT IN ('ASSET_SWAP', 'NARRATIVE_BRANCH') OR asset_uri IS NOT NULL)
);
CREATE UNIQUE INDEX ux_variation_single_default
    ON variation_options(timeline_event_id) WHERE is_default = TRUE;
CREATE INDEX ix_variation_options_event_approved
    ON variation_options(timeline_event_id, is_approved, sort_order);


-- -------------------------------------------------------------------------------------
