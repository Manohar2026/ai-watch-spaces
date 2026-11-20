# M4 Watch Space Lifecycle

M4 starts the non-AI watch-party path: create a room for a published title, invite a friend, manage the room lobby, and synchronize sample-title playback. Subtitle and timeline features are not required to create or start a room.

## API

All routes require a valid bearer access token.

| Method and route | Behavior |
|---|---|
| `POST /api/v1/watch-spaces` | Create a room for a published title. Body: `{ "titleId": "...", "name": "...", "privacy": "INVITE_ONLY", "maxParticipants": 25 }`. Returns the room and a newly generated invite code. The code is shown only in this response. |
| `POST /api/v1/watch-spaces/join` | Join using `{ "inviteCode": "XXXXX-XXXXX" }`. |
| `POST /api/v1/watch-spaces/{id}/join` | Join by room link for a `PUBLIC_LINK` room; the invite code is optional for public-link rooms and required for invite-only rooms. |
| `GET /api/v1/watch-spaces/{id}` | Read room and active membership details. Room members can read the room. |
| `POST /api/v1/watch-spaces/{id}/start` | Host starts a scheduled room. This does not require a current timeline or subtitle track. |
| `POST /api/v1/watch-spaces/{id}/end` | Host ends a room and closes active memberships. |
| `POST /api/v1/watch-spaces/{id}/leave` | A viewer leaves; if the host leaves, the room ends. |
| `POST /api/v1/watch-spaces/{id}/playback` | Host updates shared playback. Body: `{ "playbackState": "PLAYING", "positionMs": 12000 }`. Use `PAUSED` to pause or send a position to seek. |
| `POST /api/v1/watch-spaces/{id}/heartbeat` | Active room members report presence. The client sends this every 10 seconds; a member is shown online for 30 seconds after the last heartbeat. |
| `GET /api/v1/watch-spaces/{id}/chat?afterSequence=0&limit=50` | Read recent durable room messages, ordered by room sequence. Subsequent polls pass the latest `sequenceNo`. |
| `POST /api/v1/watch-spaces/{id}/chat` | Send `{ "body": "Hello everyone" }`. Plain text only, up to 2,000 characters, rate-limited to 10 messages per minute per user and room. |

The room response includes the title's media URI and duration, the caller's role, the room status, shared playback state and timestamp, and the active member list with online status. Clients poll the room every 1.2 seconds and correct playback drift against the server timestamp. Chat uses durable messages plus 1.6-second polling; presence uses 10-second heartbeats and a 30-second expiry window. This first pass uses authenticated HTTP polling; WebSocket delivery is not implemented yet.

## Local configuration

Set `INVITE_CODE_PEPPER` to a secret value with at least 32 UTF-8 bytes. For example, generate one with `openssl rand -base64 32`. Invite codes use ten Crockford Base32 characters and are stored as HMAC-SHA256 hashes. The plaintext is returned only when creating a room.

To seed the sample title, follow the opt-in steps in [m3-catalog-timeline.md](m3-catalog-timeline.md). A signed-in user ID is required as the sample timeline uploader. The room flow can then use **The First Bloom** from the published catalogue.

## Current limits

- Typing indicators, reactions, moderation, and WebSocket transport are not implemented.
- Participant changes in an open room appear on the next room poll; a backgrounded or closed client is marked away after 30 seconds.
- Create/join/start/end/leave/playback/chat changes are serialized through a bounded per-room mutation queue and persisted to PostgreSQL with room event sequence guards. Heartbeats refresh presence without adding room events.
- M4 code has not been run against a local PostgreSQL environment in this work session.

