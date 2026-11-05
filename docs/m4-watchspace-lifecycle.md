# M4 — Watch Space lifecycle

**Status:** In progress on `feature/m4-watchspace-lifecycle`. M4 was authorized on 2026-11-05. This document records the implementation checkpoint; it does not mark the milestone complete.

## First vertical slice: database schema

Flyway V3 (`V3__watchspace_lifecycle.sql`) adds the exact M4 slice from `DATABASE_SCHEMA.sql`:

- `watch_spaces` — room configuration, lifecycle status, host identity, invite-code hash, playback snapshot and guarded sequence/version counters.
- `room_subtitle_tracks` — the subtitle tracks pinned when a room starts.
- `watch_space_participants` — membership intervals and capacity accounting.
- `watch_space_moderation` — persistent mute/removal state.
- `room_events` — the closed, ordered room event ledger.

The migration does not add any new table or column beyond the approved V3 target slice. It uses PostgreSQL constraints and indexes from the canonical schema, including the partial unique index for one active membership per user per room. `room_events.occurred_at` remains required with no database default; the room mutation lane must supply the canonical mutation instant.

## Implemented functional slice: room creation

- `POST /api/v1/watch-spaces` creates a `SCHEDULED` room for a `PUBLISHED` title; omitted settings use the v0.4 defaults and the name defaults to "{title} watch party".
- The request is submitted to a lazily created, per-room FIFO lane with a bounded queue (default 256). The lane reads the injected clock once, truncates to PostgreSQL microsecond precision, and clamps against the previous room event instant.
- The single creation transaction locks the host account row (so concurrent room creation cannot bypass the five-active-room limit), verifies the published title, inserts the room and host membership, appends `ROOM_CREATED` at sequence 1 and `PARTICIPANT_JOINED` at sequence 2, then advances `last_sequence_no` with an expected-value guard.
- Invite codes use ten Crockford Base32 characters displayed as `XXXXX-XXXXX`. Only the HMAC-SHA256 digest is stored; the plaintext code is returned in the successful creation response only. The pepper comes from `INVITE_CODE_PEPPER` and must be at least 32 UTF-8 bytes.
- The lane publishes immutable state only after the persistence transaction returns successfully. A broadcaster port is present; its M4 adapter is intentionally a no-op until the WebSocket transport is implemented in M5.

## Verification

- `V3MigrationMatchesTargetSchemaIT` applies canonical V1, V2 and V3 slices to a fresh PostgreSQL database and compares tables, columns, constraints and indexes with the Flyway-migrated application database.
- `V2MigrationMatchesTargetSchemaIT` remains scoped to the V1/V2 tables so it continues to verify the earlier milestone independently after V3 is present.
- The backend CI workflow now runs for pull requests into `main` and pushes to `feature/m4-watchspace-lifecycle`.

A passing schema-parity test verifies the migration shape only. It does not verify room creation, the per-room mutation lane, membership rules, capacity races, invite-code security or lifecycle APIs. Those remain in M4.

## Remaining M4 implementation

1. Room details/preview and participant reads with privacy-aware 404 behavior.
2. Invite-code join and room-ID join, quick-rejoin, leave, start and end.
3. Lock/unlock, room settings, invite-code rotation, moderation and host transfer.
4. Complete stale-lane rehydration handling and post-commit broadcast integration.
5. Integration tests for capacity races, privacy, lock, removals, monotonic sequence numbers and guard conflicts.
6. REST reference and manual verification evidence.
