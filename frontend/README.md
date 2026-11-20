# Watch Spaces — Frontend

React + TypeScript single-page application built with Vite.

## Requirements

- Node.js 26 LTS recommended (Node.js 22.12+ is supported by the Vite toolchain).
- npm.

## Setup

```bash
cp .env.example .env
npm install
npm run dev
```

Open http://localhost:5173.

The Vite development server proxies `/api/*` requests to the Spring Boot backend at http://localhost:8080. Set `VITE_API_BASE_URL` to `/api/v1` for local development. Do not place secrets or credentials in Vite environment variables; variables prefixed with `VITE_` are included in the browser bundle.

## Checks

```bash
npm run typecheck
npm run build
npm run preview
```

## Structure

- `src/app`: application bootstrap and composition.
- `src/components`: shared UI components (add when needed).
- `src/features`: feature modules (auth, catalog, dashboard, watch spaces, player, chat, AI, etc.).
- `src/lib`: shared API client and utilities (add during API integration).
- `src/styles`: global styles and design tokens.

## Current frontend features

- Landing page and responsive authentication screens.
- Registration via `POST /api/v1/auth/register`.
- Login via `POST /api/v1/auth/login`, followed by protected `GET /api/v1/me`.
- Automatic access-token refresh using `POST /api/v1/auth/refresh` before expiry; refresh requests are single-flight to avoid triggering backend replay detection.
- Logout via `POST /api/v1/auth/logout` using the most recently rotated refresh token in the request body.
- Typed API error handling using the backend error envelope.
- Authenticated catalogue browsing with search, genre filtering, pagination, and title details.
- Inline playback preview for the published sample title.
- Create or join a watch space, view the room lobby and participants, and start or end the room.

Tokens are kept in React memory only. Access tokens are refreshed automatically while the tab remains open; reloading the tab still loses the local session. See [frontend-auth.md](../docs/frontend-auth.md) for the API contract, limitations, and manual verification steps.

The first product milestone is a normal non-AI watch-party experience: browse a title, create or join a room, watch in sync, and use chat and participant presence. The room currently synchronizes host play, pause, and seek through room polling; chat and online connection presence follow. Subtitles, timeline interactions, recommendations, analytics, and AI features are deferred until this core path is usable. AI will be integrated later against the working APIs.

The frontend CI has passed dependency installation, `npm run typecheck`, and `npm run build` for the existing implementation. The auth and catalogue flows have not yet been verified end-to-end against a locally running backend.

