# AI Watch Spaces — Full-Stack Architecture Addendum

**Status:** Full-stack direction approved by the project owner on 2026-11-04.
**Applies to:** The existing repository and the approved AI Watch Spaces PRD.
**Relationship to the existing design:** This addendum extends the backend-only implementation baseline in `SYSTEM_DESIGN.md`. It does not replace or invalidate the M1 infrastructure or M2 authentication work.

## 1. Product direction

AI Watch Spaces is a full-stack web application for people to discover content, create or join synchronized social watch spaces, communicate while watching, and use AI-assisted interactive content features.

The project will be delivered as one Git repository containing two separately built applications:

- **Backend:** the existing Java 21 / Spring Boot 4.1.2 modular monolith, retained at the repository root.
- **Frontend:** a new React + TypeScript single-page application, located in `frontend/`.

The application uses original/sample catalogue data and self-created or lawfully usable media. Netflix is a product inspiration only; there is no integration with Netflix infrastructure, accounts, catalogue, or user data.

## 2. Architecture at a glance

```text
Browser
  |
  | HTTPS / JSON REST + WebSocket
  v
React + TypeScript (frontend/)
  |-- Pages and layouts
  |-- Feature modules
  |-- API client and auth state
  |-- WebSocket client and realtime state
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
| UI | React |
| Build and development server | Vite |
| Routing | React Router |
| Server state and caching | TanStack Query |
| HTTP | A shared typed API client using fetch or Axios; choose one during frontend scaffold |
| Realtime | Native browser WebSocket API behind a feature-level client |
| Styling | Responsive CSS with reusable design tokens and components |
| Tests | Vitest and React Testing Library; browser-level tests can be added when the core flows stabilize |

Keep dependencies lean. Add a library only when a concrete feature needs it.

### Backend

Retain the current repository baseline:

- Java 21 and Spring Boot 4.1.2.
- WebFlux / Netty for HTTP and WebSocket endpoints.
- Spring Security and the existing JWT authentication implementation.
- Spring Data JPA / Hibernate with PostgreSQL 17.
- Flyway for schema migrations.
- Existing modular-monolith boundaries and blocking-JPA executor rules.

Do not replace the backend stack or reorganize its source tree as part of the frontend introduction.

## 4. Repository layout

The target layout is:

```text
ai-watch-spaces/
├── frontend/                 # React + TypeScript + Vite application
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
├── SYSTEM_DESIGN.md           # Existing detailed backend design baseline
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
| `timeline` | Timeline-driven trivia, scenes, glossary and contextual facts |
| `ai` | Grounded Q&A and source/citation presentation |
| `variations` | Subtitle alternatives, voting and approved variation display |
| `recommendations` | Personalized title recommendations and ratings |
| `analytics` | Personal and past-room analytics |
| `admin` | Catalogue and timeline metadata management |

The backend remains authoritative for permissions, room state, playback sequencing, vote outcomes, and persisted messages. The browser must not invent or independently finalize those decisions.

## 6. API and realtime integration

- REST API base path remains `/api/v1`.
- The frontend must use a single configured API base URL; do not hard-code URLs throughout feature components.
- Authenticated REST calls send the access token as a Bearer token, consistent with the existing backend contract.
- The current M2 refresh endpoint accepts a refresh token in its request body. The browser integration must respect that contract until a separately reviewed security decision changes it.
- Do not put access tokens or refresh tokens in source code, build-time public configuration, URLs, logs, or analytics events.
- Avoid persistent browser storage for credentials by default. Before implementing session persistence, document and test the selected refresh-token handling and XSS/CSRF threat model; do not silently put refresh tokens in localStorage.
- WebSocket authentication and browser-compatible credential transport must be confirmed against the backend design before the realtime UI is implemented. Do not assume browser WebSocket APIs can set arbitrary Authorization headers.
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
| F0 | Full-stack architecture and repository conventions | This addendum agreed; frontend/backend boundaries documented |
| F1 | React + TypeScript + Vite scaffold | Frontend starts, builds, lint/tests run, environment config works |
| F2 | Authentication UI and API integration | Register/login/logout/current-user flows work against M2; token handling reviewed |
| F3 | App shell, dashboard and catalogue | Responsive navigation and catalogue/dashboard screens use backend APIs |
| F4 | Watch-space lifecycle | Create, join, leave, lock, end and moderation flows integrated |
| F5 | Player and realtime foundation | WebSocket connection, authoritative playback updates, reconnect/resync |
| F6 | Chat and presence | Ordered history, live messages, typing, reactions and presence |
| F7 | Timeline and AI features | Trivia, grounded Q&A with citations, subtitle variants and voting |
| F8 | Recommendations and analytics | Ratings, recommendations, personal and room analytics |
| F9 | Admin and release hardening | Admin workflows, accessibility, security, integration/e2e tests and deployment docs |

Each milestone should include implementation, tests, manual verification instructions, and documentation updates. A milestone is not complete merely because its UI renders.

## 9. Local development target

The intended local developer experience is:

- PostgreSQL and backend dependencies started with the existing Docker Compose setup.
- Backend started with the existing Maven wrapper.
- Frontend started independently from `frontend/` using the package manager and scripts recorded in its `package.json`.
- Frontend development server proxies `/api` and the agreed WebSocket path to the backend where practical, reducing local CORS friction.
- Environment files contain only local configuration and remain git-ignored; secrets are never committed.

Exact frontend scripts, ports, proxy paths, and environment variable names will be recorded when F1 is scaffolded.

## 10. Scope and change control

This direction adds a React web client to the existing backend project. It does not authorize changing the approved database model, backend API contract, authentication semantics, module boundaries, or PRD requirements without documenting and reviewing the change.

The existing `SYSTEM_DESIGN.md` remains the detailed backend design baseline. Where it says the frontend/UI is out of scope, read that as the historical scope of the original backend-only track; this addendum supersedes that scope statement for the current full-stack project direction. Backend-specific technical constraints remain in force unless explicitly revised.

## 11. Immediate next step

Implement F1: create the React + TypeScript + Vite application under `frontend/`, add minimal developer documentation, and verify its build independently without changing the existing backend build.
