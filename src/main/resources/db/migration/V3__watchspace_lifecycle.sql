-- V3 (M4): watch spaces, membership, moderation and the room event ledger.
-- Exact V3 slice from DATABASE_SCHEMA.sql v0.4. Do not edit this migration independently of the canonical schema.

-- Watch Spaces, membership and moderation (module: watchspace)
-- -------------------------------------------------------------------------------------

-- host_user_id is the ONLY source of truth for who hosts the room (finding I7).
-- last_sequence_no / state_version are advanced only by the room mutation lane, with an
-- optimistic guard (UPDATE ... WHERE last_sequence_no = :expected). No MAX(sequence_no)+1.
CREATE TABLE watch_spaces (
    id UUID PRIMARY KEY,
    title_id UUID NOT NULL REFERENCES titles(id) ON DELETE RESTRICT,
    host_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    name VARCHAR(120) NOT NULL,
    privacy VARCHAR(20) NOT NULL DEFAULT 'INVITE_ONLY'
        CHECK (privacy IN ('PUBLIC_LINK', 'INVITE_ONLY')),
    invite_code_hash CHAR(64) NOT NULL UNIQUE,         -- HMAC-SHA256(INVITE_CODE_PEPPER, normalized code), hex
    invite_code_rotated_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED', 'LIVE', 'HOST_DISCONNECTED', 'ENDED')),
    ended_reason VARCHAR(24)
        CHECK (ended_reason IN ('HOST_ENDED', 'IDLE_TIMEOUT', 'UNSTARTED_EXPIRED')),
    playback_state VARCHAR(16) NOT NULL DEFAULT 'PAUSED'
        CHECK (playback_state IN ('PLAYING', 'PAUSED', 'BUFFERING')),  -- SEEKING is never persisted
    max_participants INTEGER NOT NULL DEFAULT 25 CHECK (max_participants BETWEEN 2 AND 50),
    ai_verbosity VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
        CHECK (ai_verbosity IN ('MINIMAL', 'NORMAL', 'CHATTY')),       -- AI answer style/length only
    trivia_enabled BOOLEAN NOT NULL DEFAULT TRUE,                       -- proactive trivia only
    voting_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    chat_muted BOOLEAN NOT NULL DEFAULT FALSE,                          -- room-wide chat mute
    scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    host_disconnected_at TIMESTAMPTZ,
    session_correlation_id UUID UNIQUE,                -- assigned at start; tags every log line of the session
    pinned_timeline_version_id UUID,                   -- assigned at start; never changes afterwards
    last_playback_position_ms INTEGER NOT NULL DEFAULT 0 CHECK (last_playback_position_ms >= 0),
    playback_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    state_version BIGINT NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    last_sequence_no BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence_no >= 0),
    UNIQUE (id, title_id),                             -- composite FK target
    UNIQUE (id, pinned_timeline_version_id),           -- composite FK target: "uses this room's pinned version"
    FOREIGN KEY (title_id, pinned_timeline_version_id)
        REFERENCES title_timeline_versions(title_id, id) ON DELETE RESTRICT,
    CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at),
    CHECK ((status = 'ENDED') = (ended_at IS NOT NULL)),
    CHECK ((status = 'ENDED') = (ended_reason IS NOT NULL)),
    CHECK (status NOT IN ('LIVE', 'HOST_DISCONNECTED') OR started_at IS NOT NULL),
    CHECK (status <> 'HOST_DISCONNECTED' OR host_disconnected_at IS NOT NULL),
    CHECK (started_at IS NULL OR session_correlation_id IS NOT NULL)
);
CREATE INDEX ix_watch_spaces_host_status ON watch_spaces(host_user_id, status, created_at DESC);
CREATE INDEX ix_watch_spaces_title_status ON watch_spaces(title_id, status);
CREATE INDEX ix_watch_spaces_scheduled ON watch_spaces(status, scheduled_at);

-- Subtitle tracks pinned for the room session (one per locale with an ACTIVE track at start).
CREATE TABLE room_subtitle_tracks (
    watch_space_id UUID NOT NULL,
    title_id UUID NOT NULL,
    locale VARCHAR(16) NOT NULL,
    subtitle_track_id UUID NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watch_space_id, locale),
    FOREIGN KEY (watch_space_id, title_id)
        REFERENCES watch_spaces(id, title_id) ON DELETE RESTRICT,
    FOREIGN KEY (subtitle_track_id, title_id, locale)
        REFERENCES subtitle_tracks(id, title_id, locale) ON DELETE RESTRICT
);

-- One row per membership interval (join .. leave). Room role is NOT stored here:
-- effective role = HOST iff user_id = watch_spaces.host_user_id, else VIEWER.
CREATE TABLE watch_space_participants (
    id UUID PRIMARY KEY,
    watch_space_id UUID NOT NULL REFERENCES watch_spaces(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),   -- updated on connect/disconnect only, never per heartbeat
    left_at TIMESTAMPTZ,
    leave_reason VARCHAR(24)
        CHECK (leave_reason IN ('LEFT', 'REMOVED', 'GRACE_EXPIRED', 'ROOM_ENDED')),
    CHECK (left_at IS NULL OR left_at >= joined_at),
    CHECK ((left_at IS NULL) = (leave_reason IS NULL))
);
CREATE UNIQUE INDEX ux_active_room_membership
    ON watch_space_participants(watch_space_id, user_id)
    WHERE left_at IS NULL;
CREATE INDEX ix_room_participants_room_joined
    ON watch_space_participants(watch_space_id, joined_at);
CREATE INDEX ix_room_participants_user_joined
    ON watch_space_participants(user_id, joined_at DESC);

-- Per-room, per-user moderation state that survives leave/rejoin and reconnects (finding I10).
-- A user with removed_at set can never rejoin this room.
CREATE TABLE watch_space_moderation (
    watch_space_id UUID NOT NULL REFERENCES watch_spaces(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    is_muted BOOLEAN NOT NULL DEFAULT FALSE,
    muted_at TIMESTAMPTZ,
    muted_by UUID REFERENCES app_users(id) ON DELETE RESTRICT,
    removed_at TIMESTAMPTZ,
    removed_by UUID REFERENCES app_users(id) ON DELETE RESTRICT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watch_space_id, user_id),
    CHECK (NOT is_muted OR (muted_at IS NOT NULL AND muted_by IS NOT NULL)),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);


-- -------------------------------------------------------------------------------------
-- Room event ledger (module: watchspace owns writes; other modules append through its API)
-- -------------------------------------------------------------------------------------

-- The event-type catalog is closed (finding I11). Adding a type requires a design change,
-- a Flyway migration of this CHECK, and an analytics-reconciliation test update.
-- Ephemeral traffic (sync ping/report, typing, reactions, presence quality) is never stored.
CREATE TABLE room_events (
    id UUID PRIMARY KEY,
    watch_space_id UUID NOT NULL REFERENCES watch_spaces(id) ON DELETE RESTRICT,
    sequence_no BIGINT NOT NULL CHECK (sequence_no > 0),
    actor_user_id UUID REFERENCES app_users(id) ON DELETE RESTRICT,   -- NULL for system actions
    event_type VARCHAR(64) NOT NULL CHECK (event_type IN (
        'ROOM_CREATED', 'ROOM_STARTED', 'ROOM_ENDED', 'ROOM_SETTINGS_UPDATED',
        'ROOM_LOCK_CHANGED', 'INVITE_CODE_ROTATED', 'RUNTIME_RECOVERED',
        'PARTICIPANT_JOINED', 'PARTICIPANT_LEFT', 'PARTICIPANT_REMOVED', 'PARTICIPANT_MUTE_CHANGED',
        'HOST_DISCONNECTED', 'HOST_RECONNECTED', 'HOST_TRANSFERRED', 'HOST_PROMOTED',
        'PLAYBACK_PLAY', 'PLAYBACK_PAUSE', 'PLAYBACK_SEEK',
        'PLAYBACK_BUFFERING_STARTED', 'PLAYBACK_BUFFERING_ENDED',
        'CHAT_MESSAGE_POSTED',
        'AI_QUESTION_CREATED', 'AI_ANSWER_PUBLISHED', 'AI_FACT_PINNED', 'AI_FACT_UNPINNED',
        'TRIVIA_SURFACED',
        'VOTE_OPENED', 'VOTE_CLOSED', 'VOTE_CANCELLED', 'VARIATION_APPLIED'
    )),
    playback_position_ms INTEGER CHECK (playback_position_ms IS NULL OR playback_position_ms >= 0),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,        -- references only; never duplicates chat bodies
    idempotency_key VARCHAR(200),                      -- e.g. "cmd:<userId>:<requestId>", "trivia:<timelineEventId>"
    correlation_id UUID,                               -- request/command correlation id
    occurred_at TIMESTAMPTZ NOT NULL,                  -- the mutation's canonical instant; no DB default (v0.3)
    UNIQUE (watch_space_id, sequence_no)
);
CREATE UNIQUE INDEX ux_room_event_idempotency
    ON room_events(watch_space_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- At most one creation/start/end event per room, so analytics boundaries are unambiguous (v0.3).
CREATE UNIQUE INDEX ux_room_event_lifecycle_once
    ON room_events(watch_space_id, event_type)
    WHERE event_type IN ('ROOM_CREATED', 'ROOM_STARTED', 'ROOM_ENDED');
CREATE INDEX ix_room_events_type_time
    ON room_events(watch_space_id, event_type, occurred_at);
