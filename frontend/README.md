# AI Watch Spaces — Frontend

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
- Logout via `POST /api/v1/auth/logout` using the refresh token in the request body.
- Typed API error handling using the backend error envelope.

Tokens are kept in React memory only. Refresh-token rotation is not yet used by the UI, and reloading the tab loses the local session. See [../../docs/frontend-auth.md](../docs/frontend-auth.md) for the API contract, limitations, and manual verification steps.

These flows are implemented but have not yet been verified end-to-end from this environment.
