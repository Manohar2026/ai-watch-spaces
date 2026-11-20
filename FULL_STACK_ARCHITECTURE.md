# Watch Spaces — Full-Stack Architecture

**Status:** Full-stack direction approved by the project owner on 2026-11-04.
**Applies to:** The existing repository and the approved AI Watch Spaces PRD.
**Relationship to the existing design:** This addendum extends the backend-only implementation baseline in `SYSTEM_DESIGN.md`. It does not replace or invalidate the M1 infrastructure or M2 authentication work.

## 1. Product direction

Watch Spaces is a full-stack web application for people to discover sample content, create or join synchronized watch spaces, and communicate while watching. The product will be built and demonstrated as a normal, non-AI site first. AI-assisted features may be integrated later against the working catalogue, timeline, room, playback, and chat APIs.

### Immediate MVP scope

- Account registration, sign-in, and sign-out.
- Browse the published sample catalogue.
- Create a watch space for a title and share an invite code/link.
- Join and leave a watch space.
- Play a sample title with host-controlled, server-authoritative playback synchronization.
- Show room participants and live chat.

Defer subtitle tracks and variants, timeline panels and trivia, AI Q&A, recommendations, voting, analytics, and admin timeline authoring. Keep the existing catalogue and timeline foundation in place, but do not make these deferred features prerequisites for the watch-party MVP. The AI phase should integrate with the stable APIs after the normal product is usable.

The project will be delivered as one Git repository containing two separately built applications:

- **Backend:** the existing Java 21 / Spring Boot 4.1.2 modular monolith, retained at the repository root.
- **Frontend:** a React + TypeScript single-page application built with Vite, located in `frontend/`.

The application uses original/sample catalogue data and self-created or lawfully usable media. Netflix is a product inspiration only; there is no integration with Netflix infrastructure, accounts, catalogue, or user data.

## 2. Architecture at a glance

```text
Browser
  |
  | HTTPS / authenticated JSON REST (HTTP polling for MVP)
  v
React + TypeScript (frontend/)
  |-- Pages and layouts
  |-- Feature modules
  |-- API client and auth state
  |-- Room, chat and presence polling state
  v
Spring Boot 4.1.2 modular monolith (repository root)
  |-- auth / users
  |-- catalog / timeline
  |-- watchspace / playback
  |-- chat / realtime
  |-- ai / variations / recommendations
  |-- analytics / admin
  v
PostgreSQL 17
```

The frontend and backend are separate deployable applications, but they are developed and versioned together in this monorepo. The backend remains a modular monolith; this project is not being split into microservices.

## 3. Technology decisions

### Frontend

| Area | Decision |
|---|---|
| Language | TypeScript |
| UI | React 19 |
| Build and development server | Vite 8 |
| Routing | React Router (add when page navigation is implemented) |
| Server state and caching | TanStack Query (add when API integration begins) |
| HTTP | Shared typed API client; choose fetch or Axios during auth integration |
| Realtime | Authenticated HTTP polling for MVP; WebSocket transport is deferred |
| Styling | Responsive CSS with reusable design tokens and components |
| Tests | Vitest and React Testing Library; browser-level tests can be added when core flows stabilize |

Keep dependencies lean. Add a library only when a concrete feature needs it.

### Backend

Retain the current repository baseline:

- Java 21 and Spring Boot 4.1.2.
- WebFlux / Netty for REST endpoints. The current MVP uses authenticated HTTP polling; WebSocket transport is deferred.
- Spring Security and the existing JWT authentication implementation.
- Spring Data JPA / Hibernate with PostgreSQL 17.
- Flyway for schema migrations.
- Existing modular-monolith boundaries and blocking-JPA executor rules.

Do not replace the backend stack or reorganize its source tree as part of the frontend introduction.

## 4. Repository layout

The current frontend scaffold is intentionally small. The target layout is:

```text
ai-watch-spaces/
├── frontend/
│   ├── public/
│   ├── src/
│   │   ├── app/               # App bootstrap, router, providers
│   │   ├── components/        # Shared UI components
│   │   ├── features/          # Feature-oriented screens and logic
│   │   ├── lib/               # API client, config, utilities
│   │   ├── hooks/
│   │   ├── styles/
│   │   └── tests/
│   ├── package.json
│   └── vite.config.ts
├── src/                       # Existing Spring Boot backend; keep in place
├── docs/
├── scripts/
├── pom.xml
├── SYSTEM_DESIGN.md
├── DATABASE_SCHEMA.sql
├── FULL_STACK_ARCHITECTURE.md
└── README.md
```

The directory names above describe the intended frontend structure; they are not all created yet.

## 5. Frontend feature boundaries

Organize frontend code by product feature rather than creating one large collection of generic components.

| Feature | Responsibilities |
|---|---|
| `auth` | Registration, login, logout, refresh/session restoration, current user |
| `catalog` | Browse, search, title details |
| `dashboard` | Recent and upcoming watch spaces, quick rejoin |
| `watchspace` | Create/join room, room lifecycle, host and participant controls |
| `player` | Media playback UI, authoritative playback state, drift display/correction |
| `chat` | Persisted messages, reactions, typing and reconnect history |
| `timeline` | Deferred metadata foundation; no timeline UI in the first MVP |
| `ai` | Later integration phase: grounded Q&A and source/citation presentation |
| `variations` | Deferred subtitle alternatives and voting |
| `recommendations` | Deferred |
| `analytics` | Deferred |
| `admin` | Later catalogue/timeline management; sample data is sufficient for the first MVP |

The backend remains authoritative for permissions, room state, playback sequencing, vote outcomes, and persisted messages. The browser must not invent or independently finalize those decisions.

## 6. API and realtime integration

- REST API base path remains `/api/v1`.
- The frontend uses `VITE_API_BASE_URL` for its API base URL; local default is `/api/v1`.
- Vite proxies `/api/*` to the local Spring Boot server at `http://localhost:8080`.
- Authenticated REST calls send the access token as a Bearer token, consistent with the existing backend contract.
- The current M2 refresh endpoint accepts a refresh token in its request body. The browser integration must respect that contract until a separately reviewed security decision changes it.
- Do not put access tokens or refresh tokens in source code, build-time public configuration, URLs, logs, or analytics events.
- Avoid persistent browser storage for credentials by default. Before implementing session persistence, document and test the selected refresh-token handling and XSS/CSRF threat model; do not silently put refresh tokens in localStorage.
- The current MVP uses authenticated HTTP polling: room snapshots approximately every 1.2 seconds, chat approximately every 1.6 seconds, and presence heartbeats every 10 seconds. See [docs/m4-watch-spaces.md](docs/m4-watch-spaces.md) for the API contract.
- Do not add WebSocket endpoints or clients to the MVP. WebSocket authentication, reconnect, backpressure and token-renewal behavior belong to a later, separately planned transport upgrade.
- Configure explicit development and production CORS/origin allowlists. Never use wildcard origins with credentials.
- Map backend error envelopes into consistent user-facing states while retaining correlation IDs for debugging.

## 7. User experience principles

- Responsive layouts for desktop and mobile browsers.
- Clear loading, empty, error, offline, reconnecting, and permission-denied states.
- Accessible controls, keyboard navigation, semantic markup, and visible focus.
- Watch-space screens prioritize the player, synchronized state, participant presence, and chat without hiding essential controls.
- AI answers visibly distinguish sourced facts from unavailable information and display the citations returned by the backend.
- Avoid implying that a local UI action succeeded until the backend confirms it.

## 8. Delivery sequence

Build and verify in small end-to-end increments. Do not build every screen before connecting it to real backend behavior.

| Milestone | Scope | Exit criteria |
|---|---|---|
| F0 | Full-stack architecture and repository conventions | Completed: architecture direction documented |
| F1 | React + TypeScript + Vite scaffold | Scaffold committed; CI/local dependency install, typecheck and production build still need to pass |
| F2 | Authentication UI and API integration | Register/login/logout/current-user and in-memory automatic refresh code committed; end-to-end browser verification and persistent session-restoration design remain open |
| F3 | App shell, dashboard and catalogue | Initial React catalogue UI consumes the published-title list/detail APIs; CI and local browser verification pending |
| F4 | Watch-space lifecycle | Create, join, leave, start and end a room; share invite codes |
| F5 | Synchronized player and realtime foundation | Server-authoritative play/pause/seek with room-snapshot polling and drift correction |
| F6 | Chat and presence | Ordered chat history, message polling, participant presence and heartbeat expiry |
| F7 | MVP polish and release | Responsive room UI, accessibility, security, setup and demo instructions |
| Later | Timeline, subtitles, recommendations, analytics and AI integration | Add only after the non-AI watch-party path works end to end |

Each milestone should include implementation, tests, manual verification instructions, and documentation updates. A milestone is not complete merely because its UI renders.

## 9. Local development target

The intended local developer experience is:

- PostgreSQL and backend dependencies started with the existing Docker Compose setup.
- Backend started with the existing Maven wrapper.
- Frontend started independently from `frontend/` using npm.
- Frontend development server proxies `/api` to the backend where practical, reducing local CORS friction.
- Environment files contain only local configuration and remain git-ignored; secrets are never committed.

Frontend commands:

```bash
cd frontend
cp .env.example .env
npm install
npm run dev
npm run typecheck
npm run build
```

The frontend development server uses port 5173 and proxies `/api` to `http://localhost:8080`. No WebSocket proxy paths are required by the current HTTP-polling MVP.

## 10. Scope and change control

This direction adds a React web client to the existing backend project. It does not authorize changing the approved database model, backend API contract, authentication semantics, module boundaries, or PRD requirements without documenting and reviewing the change.

The existing `SYSTEM_DESIGN.md` remains the detailed long-term backend design reference. Its WebSocket, in-memory room runtime and event-broadcast requirements describe the future realtime architecture; they are not implemented in the current MVP. For this MVP, PostgreSQL-backed REST polling is authoritative. Any move to WebSockets or a different room-state model requires an explicit design review and synchronized updates to the architecture, implementation and tests.

## 11. Immediate next step

Finish the current catalogue path and implement F4–F6 as one demonstrable user journey: sign in, select the sample title, create or join a room, play in sync, see who is present, and chat. Keep subtitle and AI systems out of this first room flow. Verify auth and catalogue against the local backend as part of bringing up the complete demo.

