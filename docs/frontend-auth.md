# Frontend authentication integration

**Status:** Initial browser integration implemented; local end-to-end verification is pending.
**Backend source:** M2 implementation and `docs/m2-verification.md`.

## API contract used by the frontend

Base path: `/api/v1`.

| Operation | Request | Successful response |
|---|---|---|
| Register | `POST /auth/register` with `{ email, password, displayName }` | `201 { userId }` |
| Login | `POST /auth/login` with `{ email, password }` | `200 { accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, user }` |
| Current profile | `GET /me` with `Authorization: Bearer <accessToken>` | `200 UserProfile` |
| Refresh | `POST /auth/refresh` with `{ refreshToken }` | Token pair without a `user` field |
| Logout | `POST /auth/logout` with `{ refreshToken }` | `204 No Content`; refresh token is the credential |

The frontend currently implements registration, login, current-profile verification, and logout. The refresh endpoint is not yet used by the browser UI.

### Profile response

The `UserProfile` JSON fields are:

- `id`
- `email`
- `displayName`
- `systemRole` (`USER` or `ADMIN`)
- `subtitleLocale`
- `createdAt`
- `updatedAt`

### Errors

The backend error envelope is:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid email or password.",
  "correlationId": "uuid",
  "details": []
}
```

The browser API client maps the HTTP status, `code`, `message`, `correlationId`, and `details` into an `ApiError`. The UI displays safe user-facing messages and the correlation ID for support when one is returned.

## Authentication behavior implemented

1. Registration sends the user's email, password, and display name. The registration response contains only a user ID, so the UI returns the user to sign-in instead of assuming registration logs them in.
2. Login sends email and password.
3. After login, the browser calls `GET /me` with the received access token. The dashboard is shown only after that protected request succeeds.
4. If profile verification fails after login, the browser attempts to revoke the just-issued refresh-token family by calling the public logout endpoint before showing the error.
5. Logout sends the refresh token to the public logout endpoint and clears local UI state whether or not the network request succeeds.

## Browser token handling limitation

Tokens are held only in React memory. They are not written to `localStorage`, `sessionStorage`, cookies, URLs, logs, or Vite environment variables.

Consequences:

- Reloading or closing the tab loses the browser's access to the current session.
- Refresh-token rotation is automatic only while the browser tab remains open.
- A failed logout network call clears the UI locally, but the server-side refresh token can remain active until its expiry.
- This is an intentionally limited development-stage approach, not a complete production session-restoration design.

Before adding persistent login, decide and document the browser threat model and a suitable refresh-token transport. Do not silently move refresh tokens into browser storage. Any backend cookie/CSRF change must be reviewed against the current approved backend contract.

## Registration client-side checks

The frontend pre-checks the backend password policy: 12–128 characters and at most 72 UTF-8 bytes. The backend remains authoritative and validates all requests. The display name is trimmed in the UI; the backend applies its own plain-text normalization and 1–80 code-point validation.

## Verification still required

Run the backend with PostgreSQL and a valid `JWT_SIGNING_KEY`, then run the frontend. Verify:

1. Register with a new email → HTTP 201 and the UI returns to sign-in.
2. Login with that account → HTTP 200, followed by authenticated `GET /me`.
3. Wrong password → safe `INVALID_CREDENTIALS` message.
4. Sign out → HTTP 204; the browser returns to the landing page.
7. Wait until shortly before access-token expiry and confirm the frontend sends one refresh request and subsequent `/me` calls still succeed.
8. Confirm a refresh-token replay/revocation 401 clears the local session.

No end-to-end browser test has been run from this environment.
