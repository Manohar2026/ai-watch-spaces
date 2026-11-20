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

The room response includes the title's media URI and duration, the caller's role, the room status, shared playback state and timestamp, and the active member list. Clients poll the room every 1.2 seconds and correct playback drift against the server timestamp. The host controls play, pause, and seek; viewers follow. This first pass uses HTTP polling; WebSocket delivery and connection-based presence are not implemented yet.

## Local configuration

Set `INVITE_CODE_PEPPER` to a secret value with at least 32 UTF-8 bytes. For example, generate one with `openssl rand -base64 32`. Invite codes use ten Crockford Base32 characters and are stored as HMAC-SHA256 hashes. The plaintext is returned only when creating a room.

To seed the sample title, follow the opt-in steps in [m3-catalog-timeline.md](m3-catalog-timeline.md). A signed-in user ID is required as the sample timeline uploader. The room flow can then use **The First Bloom** from the published catalogue.

## Current limits

- Chat and WebSocket presence are not implemented.
- Participant changes in an open room appear on the next room poll.
- Create/join/start/end/leave/playback changes are serialized through a bounded per-room mutation queue and persisted to PostgreSQL with room event sequence guards.
- M4 code has not been run against a local PostgreSQL environment in this work session.

