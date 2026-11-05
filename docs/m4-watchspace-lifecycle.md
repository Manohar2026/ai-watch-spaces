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
- `POST /api/v1/watch-spaces/{id}/leave` returns `204 No Content`, closes the caller's active membership with `LEFT` and appends `PARTICIPANT_LEFT`; retrying a completed leave is a no-op and does not append another event.
- `POST /api/v1/watch-spaces/{id}/start` is host-only, requires the host to remain an active member, and accepts only `SCHEDULED` rooms with a `CURRENT`, valid title timeline. It pins that timeline and every `ACTIVE` subtitle track, assigns `session_correlation_id`, and transitions to `LIVE` with playback paused at zero. Repeating start after the room is `LIVE` returns `409 INVALID_PLAYBACK_TRANSITION`.
- `POST /api/v1/watch-spaces/{id}/end` is host-only. It closes all active memberships with `ROOM_ENDED`, persists `HOST_ENDED`, and appends the terminal `ROOM_ENDED` ledger event. Non-members of `INVITE_ONLY` rooms receive 404 on lifecycle endpoints; an ordinary room member who is not the host receives 403.
- `POST /api/v1/watch-spaces/{id}/lock` is host-only. It changes the lock flag and appends `ROOM_LOCK_CHANGED`; repeating the current lock value is an idempotent no-op. A lock blocks first-time joiners but does not block a previous non-removed member from quick-rejoining.
- `PATCH /api/v1/watch-spaces/{id}/settings` is a host-only partial update for `triviaEnabled`, `votingEnabled`, `aiVerbosity`, `chatMuted` and `maxParticipants`. The capacity remains in the 2–50 range and cannot be reduced below the active membership count. Only changed fields are written to `ROOM_SETTINGS_UPDATED`; an empty patch is rejected.
- `POST /api/v1/watch-spaces/{id}/invite-code` is host-only and returns a replacement `XXXXX-XXXXX` plaintext code once. The previous hash is replaced in the same transaction, the `INVITE_CODE_ROTATED` event contains no invite code, and rotations are limited to 5/hour/room.
- `POST /api/v1/watch-spaces/{id}/participants/{userId}/mute` is host-only. It upserts persistent mute state, preserves removal state, appends `PARTICIPANT_MUTE_CHANGED` only when the value changes, and leaves membership intact. Mute state is loaded into participant projections and survives leave/rejoin.
- `DELETE /api/v1/watch-spaces/{id}/participants/{userId}` is host-only. It sets `removed_at/removed_by`, closes any active membership interval with `REMOVED`, and appends `PARTICIPANT_REMOVED`. Repeating removal is an idempotent no-op. The host cannot mute or remove themself.
- The M4 broadcaster remains a no-op adapter. Closing a removed user's WebSocket with code 4411 and enforcing `CHAT_MUTED` on chat commands require the M5 realtime/chat adapters.
- The request is submitted to a lazily created, per-room FIFO lane with a bounded queue (default 256). The lane reads the injected clock once, truncates to PostgreSQL microsecond precision, and clamps against the previous room event instant.
- The single creation transaction locks the host account row (so concurrent room creation cannot bypass the five-active-room limit), verifies the published title, inserts the room and host membership, appends `ROOM_CREATED` at sequence 1 and `PARTICIPANT_JOINED` at sequence 2, then advances `last_sequence_no` with an expected-value guard.
- Invite codes use ten Crockford Base32 characters displayed as `XXXXX-XXXXX`. Only the HMAC-SHA256 digest is stored; the plaintext code is returned in the successful creation response only. The pepper comes from `INVITE_CODE_PEPPER` and must be at least 32 UTF-8 bytes.
- The join transaction locks the room row, rechecks status/removal/privacy/lock/capacity, inserts membership and `PARTICIPANT_JOINED`, then increments `state_version` and `last_sequence_no` with expected-value guards. Same-room joins are serialized through the lane; concurrent capacity tests verify that the host seat is counted and no over-capacity membership is committed.
- The lane publishes immutable state only after the persistence transaction returns successfully. A broadcaster port is present; its M4 adapter is intentionally a no-op until the WebSocket transport is implemented in M5.
- On an optimistic `CONFLICT_RETRY`, the lane invalidates its cached snapshot after the failed transaction; the rejected request receives the conflict response, and the next command rehydrates the room from PostgreSQL.
- Startup recovery runs before readiness can report `UP`. Persisted `LIVE` and `HOST_DISCONNECTED` rooms are reconciled through their normal lanes: playback is paused at the stored position, status becomes `HOST_DISCONNECTED`, and `RUNTIME_RECOVERED` is appended (with `PLAYBACK_PAUSE` only when recovery found `PLAYING`). The realtime health indicator remains `OUT_OF_SERVICE` until this pass completes.
- A scheduled expiry sweep checks `SCHEDULED` rooms using `scheduled_at`, or `created_at` when no schedule exists, then rechecks the deadline under the room lane before closing memberships and appending the final `ROOM_ENDED` event with reason `UNSTARTED_EXPIRED`.

## Verification

- `V3MigrationMatchesTargetSchemaIT` applies canonical V1, V2 and V3 slices to a fresh PostgreSQL database and compares tables, columns, constraints and indexes with the Flyway-migrated application database.
- `V2MigrationMatchesTargetSchemaIT` remains scoped to the V1/V2 tables so it continues to verify the earlier milestone independently after V3 is present.
- `WatchSpaceCreateIT` verifies room creation, hashed one-time invites, member details, participant output, privacy preview/404 and the host's five-active-room limit.
- `WatchSpaceJoinIT` verifies both join routes, idempotence, public-link joins, quick-rejoin while locked, locked-room and removed-user rejection, plus 20 concurrent joins into a five-member room (host included) without exceeding capacity.
- `WatchSpaceLifecycleIT` verifies leave idempotence, the no-current-timeline rejection, timeline/subtitle pinning on start, paused-at-zero initialization, rejection of repeated start, end membership closure and the final `ROOM_ENDED` ledger event.
- `WatchSpaceControlsIT` verifies lock/quick-rejoin semantics, settings partial updates and capacity validation, host-only controls, invite rotation invalidating the old code, the 5/hour limit and that invite plaintext never reaches the event ledger.
- `WatchSpaceModerationIT` verifies mute persistence across leave/rejoin, unmute, terminal removal and rejoin rejection, idempotent repeat removal, self-moderation prevention and host-only authorization.
- `WatchSpaceUnstartedExpiryIT` verifies the 24-hour unscheduled expiry, `scheduled_at` precedence, membership closure, terminal event reason and repeat-sweep idempotence.
- `WatchSpaceStartupRecoveryIT` verifies recovery of playing and already-paused rooms, preservation of the persisted playback position/session ID, timestamp reuse, event ordering and state/sequence increments.
- `RoomLaneTest` verifies cached-state invalidation and DB rehydration after `CONFLICT_RETRY`, plus one `Clock` read per mutation, microsecond truncation and backwards-clock clamping.
- `docs/m4-watchspace-api.md` documents the implemented M4 Watch Space REST routes, request/response shapes, authorization behavior, current presence limitation and error envelope.
- The backend CI workflow now runs for pull requests into `main` and pushes to `feature/m4-watchspace-lifecycle`.

A passing schema-parity test verifies the migration shape only. It does not verify room creation, the per-room mutation lane, membership rules, capacity races, invite-code security or lifecycle APIs. Those remain in M4.

## Remaining M4 implementation

1. Host transfer requires an active, currently connected target; finish it with the M5 connected-session registry. Removed-user socket closure (4411) and chat enforcement of `CHAT_MUTED` also wait for M5 adapters.
2. Idle auto-end and host/participant grace timers require the M5 connected-session registry; scheduled/unstarted expiry is implemented in M4.
3. Post-commit broadcast delivery requires the M5 WebSocket broadcaster. M4 stale-lane invalidation is implemented; a conflict is returned to the caller and the next command reloads the persisted snapshot.
4. Integrate the settings side effect that cancels an open vote when voting is disabled once the vote tables/migration and vote lifecycle exist; add PostgreSQL coverage for a zero-row optimistic-guard conflict and transaction rollback.
5. REST reference and manual verification evidence.
