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

The current screen is a visual scaffold only. It does not yet call backend APIs or implement authentication.
