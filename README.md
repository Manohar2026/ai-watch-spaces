# AI Watch Spaces

AI Watch Spaces is a full-stack web application for synchronized social watch spaces with chat, interactive timeline content, AI-assisted Q&A, subtitle variations, recommendations, and analytics.

**Project direction:** Java full-stack application with a React frontend and Spring Boot backend.

## Architecture

- **Frontend:** React 19 + TypeScript + Vite 8, located in `frontend/`.
- **Backend:** Java 21 / Spring Boot 4.1.2 modular monolith, retained at the repository root.
- **Database:** PostgreSQL 17 with Flyway migrations.
- **Realtime:** WebSocket-based watch-space synchronization, chat, and presence.
- **API base path:** `/api/v1`.

See [FULL_STACK_ARCHITECTURE.md](FULL_STACK_ARCHITECTURE.md) for the approved full-stack architecture, frontend/backend boundaries, integration rules, and delivery milestones.

The detailed backend design remains in [SYSTEM_DESIGN.md](SYSTEM_DESIGN.md), and the target database model remains in [DATABASE_SCHEMA.sql](DATABASE_SCHEMA.sql). The full-stack addendum extends the original backend-only scope without discarding the existing backend decisions.

## Current implementation status

- **Backend M4 (implemented; follow-on integrations remain):** Flyway V3, the serialized room mutation lane, invite-code hashing/rotation, room creation, privacy-aware reads, participant listing, both join routes, quick-rejoin, transaction-guarded capacity, leave/start/end, lock/unlock, partial room settings, persistent mute/removal, startup recovery, scheduled-room expiry and graceful shutdown are implemented on `feature/m4-watchspace-lifecycle`. Backend CI previously passed on commit `8618a58`; no backend source changed during the subsequent F4 work. Host transfer, realtime socket effects and connection-dependent timers require M5; vote-cancellation integration depends on the later vote lifecycle. Local browser verification of the main M4 REST flows was completed on 2026-11-06; see [docs/m4-watchspace-lifecycle.md](docs/m4-watchspace-lifecycle.md).

- **Backend M1:** infrastructure and application scaffold.
- **Backend M2:** Flyway V1 and authentication (register, login, refresh with replay detection, logout, `/me`).
- **Backend M3 (draft PR #3):** Flyway V2, timeline JSON Schema v1, sample title/timeline/subtitles, published catalogue and timeline/subtitle read APIs. GitHub Actions `./mvnw -B verify` passed on the final M3 source commit: 67 unit tests and 71 integration tests, with zero failures, errors, or skips. See [docs/m3-catalog-timeline.md](docs/m3-catalog-timeline.md).
- **Frontend F0:** full-stack architecture documented.
- **Frontend F1:** React + TypeScript + Vite scaffold committed; GitHub Actions dependency install, TypeScript check, and production build passed.
- **Frontend F2:** registration, login, authenticated `/me`, logout, typed API errors, automatic refresh-token rotation, and in-memory session state implemented and manually verified against a local backend;
- **Frontend F3:** React catalogue screen with search, genre filtering, pagination and title details, integrated with M3 read APIs; CI and local browser verification passed for the seeded sample title;
- **Frontend F4 (local happy-path verification passed):** the Watch Space REST screen supports create/join, room details and participants, lock/unlock, start/end, invite rotation, leave, host mute/remove moderation, and host room settings. Frontend CI passed TypeScript checking and the production build; the local browser flow was verified on 2026-11-06 with two accounts, including removal/rejoin rejection and settings persistence. WebSocket playback/chat/presence remain M5.

Backend verification results are recorded in [docs/m2-verification.md](docs/m2-verification.md). Those are repository-recorded results; run the commands below to verify the current checkout yourself.

## Requirements

- JDK 21 (the Maven enforcer rejects other versions).
- Docker with a running daemon for Testcontainers and Compose.
- Node.js 26 LTS recommended for the frontend (Node.js 22.12+ supported by the Vite toolchain) and npm.

## Backend build and test

```bash
./mvnw verify          # enforcer + unit tests + integration tests (Testcontainers PostgreSQL 17)
./mvnw test            # unit tests only (no Docker needed)
```

## Backend local run

```bash
cp .env.example .env               # replace every placeholder; .env is git-ignored
./mvnw -B package -DskipTests
docker compose up -d --build
curl localhost:8080/actuator/health/readiness
```

The backend configuration is documented in [docs/dependency-versions.md](docs/dependency-versions.md), [docs/m2-verification.md](docs/m2-verification.md), [docs/m3-catalog-timeline.md](docs/m3-catalog-timeline.md), and `.env.example`.

## Frontend local run

```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```

Open http://localhost:5173. The Vite development server proxies `/api/*` to the backend at http://localhost:8080. Run `npm run typecheck` and `npm run build` to verify the frontend. See [frontend/README.md](frontend/README.md) for details.

The frontend includes integration code for the M2 authentication contract, including timed access-token refresh using refresh-token rotation. GitHub Actions has passed the frontend typecheck and production build. The browser flow has not yet been run end-to-end against a local backend, so verify it before treating F2 as complete. See [docs/frontend-auth.md](docs/frontend-auth.md) for the exact contract and token-handling limitations.

## Product and data boundaries

Netflix is a product inspiration only. This project does not integrate with Netflix infrastructure, accounts, catalogue, or user data. Use original/sample catalogue data and self-created or lawfully usable media assets.
