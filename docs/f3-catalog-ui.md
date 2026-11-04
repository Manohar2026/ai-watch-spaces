# F3 — Catalogue browsing and title details

**Status:** Implemented on `feature/catalog-ui`; tracked by [PR #5](https://github.com/Manohar2026/ai-watch-spaces/pull/5), targeting `main`.

## User flow

1. A signed-in user opens **Browse catalogue** from the dashboard.
2. The catalogue calls the published-title list endpoint using the current Bearer access token.
3. The user can search by title/description, filter by genre, and move through server-paginated results.
4. Opening a title loads its published details, current timeline segment, and active subtitle-track metadata for the title's default locale.
5. The title modal displays timeline events in timestamp order and the approved labels for variation points.

Feature API calls use the existing shared authenticated GET helper and the AuthProvider's single-flight refresh-token flow. A single 401 response triggers a refresh and one retry. Access and refresh tokens remain in memory; the UI does not put credentials in browser storage.

## Backend contracts used

| Request | Frontend behavior |
|---|---|
| `GET /api/v1/titles?page=0&size=12&query=&genre=` | Search and browse published titles. |
| `GET /api/v1/titles/{titleId}` | Load the selected title's metadata. |
| `GET /api/v1/titles/{titleId}/timeline?from=0&to={seconds}` | Load current timeline events for the title duration, capped at 3,600 seconds per request. API bounds are seconds; event timestamps include `ts` in seconds and `startMs`/`endMs` in milliseconds. |
| `GET /api/v1/titles/{titleId}/subtitles?locale={defaultLocale}` | Load the active subtitle-track metadata and checksum for the default locale. |

All listed endpoints require a valid Bearer access token. The frontend refreshes once and retries when an API call returns 401.

## Scope boundaries

- Search and genre filtering are server-side; the genre field is free text because M3 does not expose a separate genre-discovery endpoint.
- The timeline panel shows the current published timeline, not a room-pinned timeline. Room-pinned timeline selection depends on later watch-space milestones.
- The subtitle response is metadata only. It does not deliver VTT file bytes; the UI explicitly says this.
- The sample media URL is offered as an external link when it is a valid HTTP(S) URI. It is not an in-app video player.
- Watch-space creation, invitations, synchronized playback, chat, and WebSockets are not part of F3.

## Local verification

1. Start PostgreSQL and the Spring Boot application using the repository setup instructions.
2. Register a user and sign in through the React application.
3. To display sample catalogue data, follow the opt-in seed setup in [M3 documentation](m3-catalog-timeline.md). The seeder requires an existing user ID and is disabled by default.
4. Open **Browse catalogue**, search for `The First Bloom`, and filter by `nature`.
5. Open the title details. The sample should show the scene, trivia, glossary, subtitle variation labels, and default-locale subtitle metadata.

Frontend checks:

```bash
cd frontend
npm install
npm run typecheck
npm run build
```

A successful frontend build does not replace browser end-to-end verification against a running backend.
