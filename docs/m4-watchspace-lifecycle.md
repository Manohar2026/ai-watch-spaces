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

## Implemented functional slice: room creation, reads, joining and lifecycle

- `POST /api/v1/watch-spaces` creates a `SCHEDULED` room for a `PUBLISHED` title; omitted settings use the v0.4 defaults and the name defaults to "{title} watch party".
- `GET /api/v1/watch-spaces/{id}` returns full details to a member, a deliberately limited preview to non-members of `PUBLIC_LINK` rooms, and 404 to non-members of `INVITE_ONLY` rooms. Removed users receive `REMOVED_FROM_ROOM`.
- `GET /api/v1/watch-spaces/{id}/participants` returns active memberships, role derived from `host_user_id`, display names and persisted mute state. `connected=false` and `connectionQuality=UNKNOWN` until M5 adds the realtime session registry.
- `POST /api/v1/watch-spaces/join` resolves an invitation by HMAC digest; unknown and wrong codes return the same `INVITE_CODE_INVALID` response.
- `POST /api/v1/watch-spaces/{id}/join` supports invite-only joins with a code and public-link joins without one. Active membership is idempotent; prior members can quick-rejoin without a code even while locked; removed users cannot rejoin.
- Both join routes share the documented in-memory token buckets (10/min per authenticated user, 30/min per IP).
- `POST /api/v1/watch-spaces/{id}/leave` closes the caller's active membership with `LEFT` and appends `PARTICIPANT_LEFT`; retrying a completed leave is a no-op.
- `POST /api/v1/watch-spaces/{id}/start` is host-only and requires a `CURRENT`, valid title timeline. It pins that timeline and every `ACTIVE` subtitle track, assigns `session_correlation_id`, and transitions to `LIVE` with playback paused at zero.
- `POST /api/v1/watch-spaces/{id}/end` is host-only. It closes all active memberships with `ROOM_ENDED`, persists `HOST_ENDED`, and appends the terminal `ROOM_ENDED` ledger event.
- The request is submitted to a lazily created, per-room FIFO lane with a bounded queue (default 256). The lane reads the injected clock once, truncates to PostgreSQL microsecond precision, and clamps against the previous room event instant.
- The single creation transaction locks the host account row (so concurrent room creation cannot bypass the five-active-room limit), verifies the published title, inserts the room and host membership, appends `ROOM_CREATED` at sequence 1 and `PARTICIPANT_JOINED` at sequence 2, then advances `last_sequence_no` with an expected-value guard.
- Invite codes use ten Crockford Base32 characters displayed as `XXXXX-XXXXX`. Only the HMAC-SHA256 digest is stored; the plaintext code is returned in the successful creation response only. The pepper comes from `INVITE_CODE_PEPPER` and must be at least 32 UTF-8 bytes.
- The join transaction locks the room row, rechecks status/removal/privacy/lock/capacity, inserts membership and `PARTICIPANT_JOINED`, then increments `state_version` and `last_sequence_no` with expected-value guards. Same-room joins are serialized through the lane; concurrent capacity tests verify that the host seat is counted and no over-capacity membership is committed.
- The lane publishes immutable state only after the persistence transaction returns successfully. A broadcaster port is present; its M4 adapter is intentionally a no-op until the WebSocket transport is implemented in M5.

## Verification

- `V3MigrationMatchesTargetSchemaIT` applies canonical V1, V2 and V3 slices to a fresh PostgreSQL database and compares tables, columns, constraints and indexes with the Flyway-migrated application database.
- `V2MigrationMatchesTargetSchemaIT` remains scoped to the V1/V2 tables so it continues to verify the earlier milestone independently after V3 is present.
- `WatchSpaceCreateIT` verifies room creation, hashed one-time invites, member details, participant output, privacy preview/404 and the host's five-active-room limit.
- `WatchSpaceJoinIT` verifies both join routes, idempotence, public-link joins, quick-rejoin while locked, locked-room and removed-user rejection, plus 20 concurrent joins into a five-member room (host included) without exceeding capacity.
- `WatchSpaceLifecycleIT` verifies leave idempotence, the no-current-timeline rejection, timeline/subtitle pinning on start, paused-at-zero initialization, start idempotence, end membership closure and the final `ROOM_ENDED` ledger event.
- The backend CI workflow now runs for pull requests into `main` and pushes to `feature/m4-watchspace-lifecycle`.

A passing schema-parity test verifies the migration shape only. It does not verify room creation, the per-room mutation lane, membership rules, capacity races, invite-code security or lifecycle APIs. Those remain in M4.

## Remaining M4 implementation

1. Lock/unlock, room settings, invite-code rotation, moderation and host transfer.
2. Lifecycle timers: idle end and unstarted expiry; host socket disconnect/reconnect waits for M5 realtime sessions.
3. Complete stale-lane rehydration handling and post-commit broadcast integration.
4. Additional integration coverage for lock/removal/settings/host transfer, monotonic timestamps and guard conflicts.
5. REST reference and manual verification evidence.
