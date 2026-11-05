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

## Verification

- `V3MigrationMatchesTargetSchemaIT` applies canonical V1, V2 and V3 slices to a fresh PostgreSQL database and compares tables, columns, constraints and indexes with the Flyway-migrated application database.
- `V2MigrationMatchesTargetSchemaIT` remains scoped to the V1/V2 tables so it continues to verify the earlier milestone independently after V3 is present.
- The backend CI workflow now runs for pull requests into `main` and pushes to `feature/m4-watchspace-lifecycle`.

A passing schema-parity test verifies the migration shape only. It does not verify room creation, the per-room mutation lane, membership rules, capacity races, invite-code security or lifecycle APIs. Those remain in M4.

## Remaining M4 implementation

1. Per-room serialized mutation lane and immutable room state hydration.
2. Room create/read, invite-code join, room-ID join, leave, start and end.
3. Lock/unlock, room settings, invite-code rotation, moderation and host transfer.
4. Persist → apply → broadcast ordering and optimistic sequence guard handling.
5. Integration tests for capacity races, privacy, lock, removals, monotonic sequence numbers and guard conflicts.
6. REST reference and manual verification evidence.
