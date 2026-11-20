# M4 Watch Space Lifecycle

M4 starts the non-AI watch-party path: create a room for a published title, invite a friend, and manage the room lobby. Subtitle and timeline features are not required to create or start a room. Playback synchronization, chat, and live connection presence are later slices.

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

The room response includes the title's media URI and duration, the caller's role, the room status, and the active member list. The current member list is based on persisted room membership; WebSocket connection state is not implemented yet. The client refreshes the room to see membership changes.

## Local configuration

Set `INVITE_CODE_PEPPER` to a secret value with at least 32 UTF-8 bytes. For example, generate one with `openssl rand -base64 32`. Invite codes use ten Crockford Base32 characters and are stored as HMAC-SHA256 hashes. The plaintext is returned only when creating a room.

To seed the sample title, follow the opt-in steps in [m3-catalog-timeline.md](m3-catalog-timeline.md). A signed-in user ID is required as the sample timeline uploader. The room flow can then use **The First Bloom** from the published catalogue.

## Current limits

- Playback play, pause, seek, and synchronization are not implemented.
- Chat and WebSocket presence are not implemented.
- Participant changes in an open room require a manual refresh.
- Create/join/start/end/leave changes are serialized through a bounded per-room mutation queue and persisted to PostgreSQL with room event sequence guards.
- M4 code has not been run against a local PostgreSQL environment in this work session.

