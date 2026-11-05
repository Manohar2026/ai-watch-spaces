# Watch Space REST API — M4

This reference describes the Watch Space REST endpoints currently implemented in M4. It is based on the controller and request/response DTOs in `com.aiwatchspaces.watchspace`; it does not describe future WebSocket, playback-command, voting or host-transfer APIs.

## Base path and authentication

Base path: `/api/v1/watch-spaces`.

All endpoints in this document require an authenticated access token:

```http
Authorization: Bearer <access-token>
Content-Type: application/json
```

Room IDs and user IDs are UUIDs. Timestamps are ISO-8601 instants (for example, `2026-11-05T12:30:00Z`). Endpoints with no request body should be called without one.

## Room creation

### Create a scheduled room

`POST /api/v1/watch-spaces`

Request:

```json
{
  "titleId": "4c2d6b28-5f92-4c7b-8cd1-5a1bba4fbc00",
  "name": "Friday watch party",
  "privacy": "INVITE_ONLY",
  "maxParticipants": 25,
  "aiVerbosity": "NORMAL",
  "triviaEnabled": true,
  "votingEnabled": true,
  "scheduledAt": "2026-11-06T18:00:00Z"
}
```

Only `titleId` is required. The title must be `PUBLISHED`. Optional fields and defaults:

| Field | Allowed value / rule | Default |
|---|---|---|
| `name` | Plain text, 1–120 characters | `{title} watch party` |
| `privacy` | `INVITE_ONLY`, `PUBLIC_LINK` | `INVITE_ONLY` |
| `maxParticipants` | Integer 2–50 | `25` |
| `aiVerbosity` | `MINIMAL`, `NORMAL`, `CHATTY` | `NORMAL` |
| `triviaEnabled` | Boolean | `true` |
| `votingEnabled` | Boolean | `true` |
| `scheduledAt` | ISO-8601 instant | Unscheduled |

Success: `201 Created`.

Response fields: `watchSpaceId`, `inviteCode`, `status`, `hostUserId`, `createdAt`, and `settings` (`maxParticipants`, `aiVerbosity`, `triviaEnabled`, `votingEnabled`, `chatMuted`). The new room starts as `SCHEDULED`; the creator is its host and first active member. The invite code is displayed as `XXXXX-XXXXX`. Save it when returned: the plaintext is not returned by subsequent room reads.

## Joining and membership

### Join using an invite code

`POST /api/v1/watch-spaces/join`

```json
{ "inviteCode": "ABCDE-FGHJK" }
```

`inviteCode` is required. Success: `200 OK`, with `watchSpaceId`, `membershipId`, `userId`, `joinedAt`, `roomRole`, `alreadyMember`, and `rejoined`.

### Join by room ID

`POST /api/v1/watch-spaces/{watchSpaceId}/join`

Request body is optional. For an invite-only room, a new member must supply:

```json
{ "inviteCode": "ABCDE-FGHJK" }
```

A public-link room does not require a code. An existing active membership is returned idempotently. A previous non-removed member can quick-rejoin without a code, including while the room is locked. A first-time join is blocked when the room is locked. Room capacity is checked for both new joins and re-joins.

Success: `200 OK`, with the same join response fields as the invite-code route.

### Leave

`POST /api/v1/watch-spaces/{watchSpaceId}/leave`

No request body. Success: `204 No Content`. The active membership interval is closed. Repeating leave after a prior leave is a no-op.

## Room reads

### Get room details or public preview

`GET /api/v1/watch-spaces/{watchSpaceId}`

A current or previous non-removed member receives the full room details response. A non-member can receive a limited preview only for a `PUBLIC_LINK` room. A non-member of an `INVITE_ONLY` room receives `404 Not Found`; removed users receive `403 REMOVED_FROM_ROOM`.

The full response includes `watchSpaceId`, `titleId`, `titleName`, `titleDurationMs`, `name`, `privacy`, `status`, `hostUserId`, `myRoomRole`, `participantCount`, `activeMembershipCount`, `maxParticipants`, `aiVerbosity`, `triviaEnabled`, `votingEnabled`, `locked`, `chatMuted`, `scheduledAt`, `createdAt`, `startedAt`, `endedAt`, `playbackState`, `lastPlaybackPositionMs`, `playbackUpdatedAt`, `stateVersion`, and `lastSequenceNo`.

The non-member preview contains only `name`, `titleName`, `status`, `participantCount`, `maxParticipants`, and `locked`.

**M4 presence limitation:** `participantCount` and preview participant count are currently zero because connected-session tracking is deferred to M5. For member details, use `activeMembershipCount` for the persisted active membership total.

### List participants

`GET /api/v1/watch-spaces/{watchSpaceId}/participants`

Member-only. Returns an array of `userId`, `displayName`, `roomRole`, `connected`, `connectionQuality`, and `muted` for active members. Until M5 adds the realtime session registry, `connected` is `false` and `connectionQuality` is `UNKNOWN`.

## Host lifecycle and controls

These endpoints require the room host. Successful responses (except where noted) return the full room details response.

| Method and path | Request | Behavior |
|---|---|---|
| `POST /{watchSpaceId}/start` | No body | Starts a scheduled room only when its title has a current, valid timeline; pins the timeline and active subtitle tracks; starts playback paused at 0. |
| `POST /{watchSpaceId}/end` | No body | Ends the room, closes active memberships and appends the final `ROOM_ENDED` event. |
| `POST /{watchSpaceId}/lock` | `{"locked": true}` | Locks or unlocks the room. Repeating the current value is a no-op. |
| `PATCH /{watchSpaceId}/settings` | Partial settings object (below) | Updates only supplied fields. |
| `POST /{watchSpaceId}/invite-code` | No body | Rotates the invitation code; returns the new plaintext code once. |

Settings patch fields:

```json
{
  "triviaEnabled": false,
  "votingEnabled": true,
  "aiVerbosity": "CHATTY",
  "chatMuted": false,
  "maxParticipants": 10
}
```

Every field is optional, but at least one must be supplied. `maxParticipants` remains between 2 and 50 and cannot be reduced below the current active membership count. A no-change patch appends no event. Disabling voting does not yet cancel an open vote; the vote lifecycle is outside the implemented M4 persistence slice.

Invite-code rotation returns:

```json
{ "inviteCode": "ABCDE-FGHJK" }
```

The previous code becomes invalid immediately. The returned code is not written to the event ledger or persisted in plaintext. Rotations are limited to five per room per hour; a limit response is `429 RATE_LIMITED` and includes `Retry-After`.

## Participant moderation

| Method and path | Request | Behavior |
|---|---|---|
| `POST /{watchSpaceId}/participants/{userId}/mute` | `{"muted": true}` | Host-only persistent mute/unmute. Returns room details. |
| `DELETE /{watchSpaceId}/participants/{userId}` | No body | Host-only removal. Returns `204 No Content`; removal is terminal for that user in the room. |

The host cannot mute or remove themself. Mute state persists across leave/re-join. Removal closes an active membership and prevents future re-joining.

## Common errors

Error responses use this envelope:

```json
{
  "code": "ROOM_LOCKED",
  "message": "This room is locked to new members.",
  "correlationId": "f10b0e2f-8a5d-4da0-b6e6-7acfc1a30a11",
  "details": []
}
```

The `details` array contains `field`, `code`, and `message` for field-level validation issues.

| HTTP status | Relevant codes | Example |
|---|---|---|
| `400` | `MALFORMED_REQUEST`, `VALIDATION_FAILED` | Missing required field, invalid enum or empty settings patch |
| `401` | `UNAUTHENTICATED`, `TOKEN_EXPIRED` | Missing or expired access token |
| `403` | `FORBIDDEN`, `NOT_ROOM_HOST`, `NOT_ROOM_MEMBER`, `REMOVED_FROM_ROOM` | Host-only action by a viewer or access by a removed member |
| `404` | `NOT_FOUND` | Missing room or hidden invite-only room |
| `409` | `ROOM_ENDED`, `ROOM_LOCKED`, `ROOM_FULL`, `INVITE_CODE_INVALID`, `NO_CURRENT_TIMELINE`, `INVALID_PLAYBACK_TRANSITION`, `HOST_ROOM_LIMIT`, `CONFLICT_RETRY` | Room state or membership rule prevents the command |
| `429` | `RATE_LIMITED` | Join or invite-rotation rate limit |
| `503` | `SERVICE_BUSY`, `ROOM_BUSY` | Database executor or per-room command queue is saturated |

Wrong and malformed invite codes are deliberately indistinguishable and return `409 INVITE_CODE_INVALID`. Invite-only non-members are hidden on room read and member/lifecycle access with `404`.

## Not part of the M4 REST surface

WebSocket connection/session APIs, playback commands, chat, voting, host transfer and connection-dependent presence are not implemented by this M4 REST controller. They remain assigned to later milestones; this document must be extended when those routes are implemented.
