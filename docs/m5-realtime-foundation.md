# M5 — Realtime foundation

**Branch:** `feature/m5-realtime-foundation`  
**Authorization:** user explicitly authorized M5 on 2026-11-06.  
**Implementation status:** first vertical slice in progress; do not describe M5 as complete.

## Design decisions

The `realtime` module owns WebSocket transport, handshake, session registry and fan-out. The `watchspace` module remains the source of truth for room membership and state. Realtime access goes through `WatchSpaceRealtimePort` and its safe immutable projection; transport code must not access JPA entities, invite-code hashes or the mutation lane directly.

Browser WebSocket APIs cannot set an arbitrary `Authorization` header. Browser clients therefore obtain a ticket over the existing authenticated REST boundary:

`POST /api/v1/watch-spaces/{watchSpaceId}/connection-ticket`

The caller must be an active, non-removed member of the room; historical membership after leaving does not qualify. Ticket minting is rate-limited to 20 per user per minute by default. A ticket is 32 random bytes encoded with Base64 URL encoding, bound to one user and one room, single-use, and expires within 30 seconds or at the access-token expiry (whichever is earlier). Only its SHA-256 digest is retained in process memory, and expired entries are purged every 30 seconds. The browser offers `aiws.v1` and `aiws.ticket.<ticket>` as WebSocket subprotocols; the server selects only `aiws.v1`. Access and refresh tokens are never placed in a query string or URL.

A native/scripted client may use the Bearer authorization header described by the base WebSocket contract. The browser ticket path is the selected browser-compatible transport.

## Implemented in the first vertical slice

- A dedicated security filter authenticates `/ws/watch-spaces/**` with the one-time ticket. The normal REST resource-server filter remains responsible for Bearer JWTs.
- Ticket issuance validates the current active room membership; ticket consumption is one-time, room-bound and expiry-bound. Membership is rechecked during handshake authentication.
- `GET /ws/watch-spaces/{watchSpaceId}` is mapped through WebFlux.
- The connection sends `room.snapshot` followed by `room.chat.history` (currently empty until the chat milestone is implemented).
- Committed room-ledger events are fanned out to sockets in the same room as `room.event` messages after the database transaction commits. The transport projection includes event id, sequence number, actor, event type, playback position, payload, correlation id and occurrence time; it deliberately omits the idempotency key.
- The snapshot includes room lifecycle/settings, playback's persisted starting snapshot, sequence/state version, members, this user's role/mute state, connected flags, and access-token expiry.
- The session registry allows one active socket per user per room. A replacement closes the older socket with 4409.
- Per-session outbound messages use a bounded queue of 256. Overflow closes with 4408.
- The session registry updates the room lane's connected-session count, preventing an active socket's lane from being evicted.
- Ticket minting is rate-limited per authenticated user (20/minute by default); the per-user limit and window are configurable.
- Readiness now requires the WebSocket handler mapping to be registered, the room runtime to be responsive, completed startup recovery, and unsaturated DB and room-lane queues.
- Oversized inbound text frames close with 1009; unsupported frame types close with 4400. Other client commands are not implemented in this slice.

## Verification

Unit tests cover:
- ticket binding to user and room;
- one-time consumption;
- expiry and no extension beyond the access-token expiry;
- replacement of a same-user socket with 4409 and correct connected-session accounting.

The full backend GitHub Actions workflow is the compile/test gate. The local Docker/browser handshake was manually verified: the authenticated socket reaches `CONNECTED` and receives `room.snapshot` followed by `room.chat.history`. The committed-event fan-out is covered by focused unit tests.

## Remaining M5 work

1. Publish connection/disconnection presence updates to connected sockets and project them into participant state.
2. Add disconnect handling and the 30-second participant reconnect grace; reconnect must not create a duplicate membership or `PARTICIPANT_JOINED`.
3. Add host-disconnect pause, host grace, deterministic promotion and host reconnect behavior through the serialized room lane.
4. Add access-token renewal messages, `room.auth.expiring`, same-subject validation, and 4401 expiry close.
5. Enforce terminal close codes for room end/removal (4410/4411) and active membership revalidation.
6. Add the complete scripted JUnit WebSocket integration suite: invalid ticket, non-member, one-time ticket replay, snapshot ordering, replacement 4409, reconnect without duplicate membership, auth expiry/renewal, 4401/4409/4411.
7. Manually verify the current browser-side ticket and WebSocket client with the two local accounts, then refine connection-state handling based on observed behavior.

Playback mutations, clock synchronization/drift, chat persistence and chat enforcement belong to later milestones and must not be silently added to this slice.
