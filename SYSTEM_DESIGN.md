# AI Watch Spaces — Backend System Design

**Status:** Design baseline **v0.4 — approved by the user on 2026-10-26** (M0.2 documentation correction). **M1 implemented on 2026-10-26** (scaffold and infrastructure only; results in `docs/dependency-versions.md`; M1 corrections in Appendix D). **M2 and later require explicit authorization.**

**Source of truth:** *AI-Driven Hyper-Personalized Interactive Content & Social Watch Spaces PRD* v1.0 (September 2026). This design implements **Track B: Backend-Only** on the PRD's **Option A — Java** stack.

**Labels used in this document:**
- **[PRD]** — an explicit PRD requirement or acceptance criterion.
- **[DD]** — a design decision made here (not stated by the PRD). Deliberate deviations from the PRD are collected in §14.

**What changed from v0.1 (v0.2):** every Critical (C1–C6) and Important (I1–I26) audit finding is resolved; Appendix A maps each finding to its section. Optional findings (O1–O11) are recorded in §16 and deliberately **not** implemented.

**What changed from v0.3 (v0.4, M0.2):** `flyway-database-postgresql` is mandatory; subtitle-track integrity for `SUBTITLE_TRACK_SWAP` options and room pinning is defined (Appendix C).

**What changed from v0.2 (v0.3, M0.1):** see Appendix B. In short:
- OD-1 resolved: Spring Boot **4.1.2** on Java 21, with Jackson 3, Hibernate 7 and json-schema-validator 3.x (ADR-002), plus an M1 verification checklist for every detail not confirmed from official sources.
- Deterministic session-analytics reconciliation: one canonical instant per mutation (ADR-005, §9.4).
- Refresh-token replay: the family revocation commits before `401 REFRESH_TOKEN_REUSED` is returned (§8.2).

> The PRD uses Netflix branding as a project scenario but rules out integration with real Netflix infrastructure, accounts, catalogues or user data. Use self-created/sample titles and lawfully usable sample video only.

---

## 1. Goals and scope

The backend must support:

1. Registration/login, short-lived JWT access tokens, rotating refresh tokens with replay rejection. [PRD §10.1]
2. A title catalogue and admin-managed, validated, versioned timeline metadata. [PRD §6.2, §6.3]
3. Creating, joining (code or link), locking, leaving, ending and moderating Watch Spaces. [PRD §6.1]
4. Server-held authoritative playback state driven by the host, WebSocket fan-out, drift measurement/correction, presence with connection quality, reconnect, ordered persisted chat, reactions and typing indicators. [PRD §6.1]
5. Timeline-triggered and on-demand trivia, retrieval-grounded Q&A with cited sources, host-pinned facts, per-viewer locale subtitle alternates, host selection of approved variants and timed group voting. [PRD §5.1, §6.2]
6. Personal dashboard, past-room listing with analytics, hybrid recommendations, ratings. [PRD §6.3]
7. Proof through automated tests, a Postman collection and Java/JUnit scripted WebSocket clients instead of a UI. [PRD §4.3]
8. Reproducible setup, measured performance, API/event documentation and seed data. [PRD §12]

**Out of scope:** any frontend/UI, billing, CDN/DRM, real Netflix integrations, generation of plot/video/audio/subtitle content at watch time, multi-tenant or multi-instance deployment. [PRD §4.2]

---

## 2. Architecture decisions

### ADR-001 — Modular monolith

**Decision:** one Spring Boot application, one deployable, one PostgreSQL database, strictly separated feature modules (top-level Java packages). [DD, consistent with PRD §4.1 and §9.4]

**Why:** one engineer, one private/local deployment, four weeks. Microservices would add network contracts, distributed transactions and deployment overhead with no requirement for independent deployment. The PRD requires the real-time gateway and AI Content Engine to be *logically* separate, which module boundaries satisfy.

#### Module responsibilities

| Module | Owns |
|---|---|
| `common` | Error envelope, error codes, correlation IDs, clock abstraction, ID generation, plain-text validation, in-memory rate limiter. |
| `infrastructure` | Cross-cutting technology only: datasource/Hikari config, the bounded **database executor**, the AI executor/concurrency limiter, configured `WebClient` builder, local asset storage, structured logging, health/metrics wiring. It owns **no feature entities or repositories**. |
| `users` | `app_users` profile, locale, preferences. |
| `auth` | Registration, login, password hashing, JWT issuing/validation, `refresh_tokens` rotation/replay rejection, WebSocket token re-validation API. |
| `catalog` | `titles`, `genres`, `title_genres`. |
| `timeline` | Timeline upload/parse/validate/publish, `title_timeline_versions`, `timeline_events`, `variation_options` (writes), versioned read API and per-version event cache. |
| `playback` | **Pure domain, no Spring/DB:** playback state machine, authoritative clock maths, drift and correction policy, connection-quality classification. |
| `watchspace` | **Room runtime kernel:** per-room serialized mutation lane, in-memory authoritative room state, sequence/stateVersion allocation, the `room_events` ledger, room lifecycle, membership, moderation, invites, settings, host disconnect/promotion, recovery, idle auto-end, timers. Defines the SPIs other modules plug into (below). |
| `chat` | `chat_messages`, chat history queries, plain-text chat policy, typing/reaction validation. |
| `ai` | Retrieval, prompt construction, LLM provider adapter, grounded-answer validation, `ai_questions`, `ai_answer_sources`, `ai_pinned_facts`, proactive and on-demand trivia. |
| `variations` | `subtitle_tracks`, `subtitle_cues`, room subtitle pinning, per-viewer locale alternates, votes/ballots, host selection, `room_variation_applications`, variant suggestions. |
| `recommendations` | `title_interactions` (derived), `title_ratings`, hybrid scorer, offline evaluation. |
| `analytics` | `watch_space_analytics`, ledger-derived metrics, `/me/dashboard` and past-room listing aggregation. |
| `admin` | Admin REST controllers only; delegates to `catalog`/`timeline`; no domain logic. |
| `realtime` | WebSocket transport only: handshake, session registry, inbound parsing/limits, routing to module services, outbound fan-out, slow-consumer policy. Implements `RoomBroadcaster`. |

#### Dependency direction (acyclic, compile-time)

```mermaid
flowchart BT
    common
    infrastructure --> common
    playback --> common
    users --> infrastructure
    auth --> users
    catalog --> infrastructure
    timeline --> catalog
    watchspace --> playback
    watchspace --> timeline
    watchspace --> users
    chat --> watchspace
    ai --> watchspace
    ai --> timeline
    variations --> watchspace
    variations --> timeline
    recommendations --> watchspace
    recommendations --> catalog
    analytics --> recommendations
    analytics --> watchspace
    admin --> timeline
    realtime --> auth
    realtime --> chat
    realtime --> ai
    realtime --> variations
    realtime --> watchspace
```

(Arrows point from a module to what it depends on. Every module may also use `common` and `infrastructure`.)

Rules:

- A module calls another module only through that module's public application-service interfaces and DTOs. No module touches another module's entities or repositories.
- `watchspace` never depends on `chat`, `ai`, `variations`, `analytics`, `recommendations` or `realtime`. It reaches them through SPIs it defines and they implement (dependency inversion):
  - `RoomBroadcaster` — implemented by `realtime`; delivers committed server events to sessions.
  - `RoomTickHandler` — implemented by `ai` (proactive trivia) and `variations` (vote close at marker); invoked inside the lane on each playback tick.
  - `RoomLifecycleListener` — implemented by `variations` (cancel open votes), `recommendations` (interaction derivation) and `analytics` (snapshot); invoked after commit for lifecycle events.
- Business logic lives in application services, never in controllers or WebSocket handlers.
- Boundary enforcement in M1: package structure, package-private implementation classes, and code review. An automated architecture test tool is deferred (O2).

**Extraction seams for the future (not for the MVP):** realtime gateway (needs distributed room ownership + pub/sub), AI Content Engine, recommendation jobs.

### ADR-002 — Technology and version pinning

**Decision:** Java 21 LTS, Spring Boot **4.1.2**, Maven, PostgreSQL 17. [DD; stack is PRD Option A. Resolves OD-1 in v0.3.]

**Why 4.1.2 rather than 3.5.16:** the 3.5 line ended free OSS support on 2026-06-30 (3.5.16 was its final OSS release). 4.1.2 was released on 2026-10-23 and is on a supported line. Moving now, before any code exists, avoids a forced major migration (Jackson 2→3, Hibernate 6→7, Spring Framework 6→7) in the middle of the build.

#### Verified from official sources during M0.1

| Fact | Source |
|---|---|
| Spring Boot 4.1.2 was released 2026-10-23 and is available from Maven Central. | spring.io release announcement |
| 4.1.2 requires Java 17+ (compatible up to Java 27), Spring Framework 7.0.10+, and Maven 3.6.3+. Java 21 is therefore supported. | Spring Boot 4.1 "System Requirements" documentation |
| Spring Boot 4 uses **Jackson 3** as its preferred JSON library. The Jackson 2 support module ships only in deprecated form. Jackson properties moved under `spring.jackson.json.*`. | Spring Boot 4.0 Migration Guide |
| Modular starters: Flyway auto-configuration needs `spring-boot-starter-flyway`. The resource-server starter is `spring-boot-starter-security-oauth2-resource-server` (the old `spring-boot-starter-oauth2-resource-server` name is deprecated). Test starters follow `spring-boot-starter-<technology>-test` and bring `spring-boot-starter-test` transitively. `@WithMockUser` needs `spring-boot-starter-security-test`. `hibernate-jpamodelgen` is replaced by `hibernate-processor`. | Spring Boot 4.0 Migration Guide |
| 4.1 removes everything deprecated in 4.0. The 4.1.0 dependency upgrades include Flyway 12.4.0, Spring Security 7.1.0 and Spring Data BOM 2026.0.0. 4.1 adds an optional lazy JDBC connection-fetch mode (`spring.datasource.connection-fetch`). | Spring Boot 4.1 Release Notes |
| `com.networknt:json-schema-validator` **3.x** targets Java 17+ and **Jackson 3**; 2.x targets Jackson 2. Minor releases may contain breaking API changes. 3.0.7 appears in the README; 3.0.8 is the newest version shown on Maven Central. | networknt GitHub README; Maven Central listing |

Exact BOM-managed versions for 4.1.2 (Hibernate ORM, Jackson 3, Testcontainers, PostgreSQL JDBC, HikariCP, SnakeYAML) could not be read from official pages reachable from the design environment. **They are not claimed here.** They are M1 verification items (table below).

#### Pinned choices

| Component | Pinned choice | Notes |
|---|---|---|
| JDK | Java 21 LTS (`maven.compiler.release=21`) | Within 4.1.2's supported range (17–27). |
| Build | Maven 3.9.x via Maven Wrapper | `maven-enforcer-plugin`: `requireJavaVersion [21,22)`, `requireMavenVersion [3.9,)`, `dependencyConvergence`, `banDuplicatePomDependencyVersions`. |
| Spring Boot | `spring-boot-starter-parent` **4.1.2** | Supported line. Never override individual Spring/BOM-managed versions. |
| Spring Framework / Security / Data | Managed by the Boot BOM (Framework 7.0.x ≥ 7.0.10; Security 7.1.x; Data 2026.0.x) | Exact patches recorded at M1. |
| Hibernate ORM | Managed by the Boot BOM — **Hibernate ORM 7.x** (exact minor/patch verified at M1) | JSONB via `@JdbcTypeCode(SqlTypes.JSON)` with a **Jackson 3** format mapper (M1-V5). Use `hibernate-processor` if a metamodel generator is ever needed. |
| HikariCP, PostgreSQL JDBC | Managed by the Boot BOM | `spring.datasource.connection-fetch` stays at its eager default (M1-V12). |
| Flyway | `spring-boot-starter-flyway` (managed, 12.x line per 4.1.0 notes) **+ `org.flywaydb:flyway-database-postgresql` (mandatory, version managed by the Boot BOM)** | PostgreSQL 17 is the only target database; the PostgreSQL support module is always declared. M1-V2 verifies resolution and migrations. |
| Jackson | **Jackson 3** (`tools.jackson.*` packages), managed by the Boot BOM | **No Jackson 2 `jackson-databind` on the classpath** and no `spring-boot-jackson2` module (M1-V6). |
| JWT | `spring-boot-starter-security-oauth2-resource-server` (Nimbus JOSE) | HS256 issuing/validation without a third-party JWT library. |
| JSON Schema validation | `com.networknt:json-schema-validator` **3.0.x**, exact patch pinned at M1 (≥ 3.0.8 if it converges) | 3.x = Jackson 3 line; 2.x is incompatible with this stack. Pin an exact version; review release notes before any upgrade. |
| YAML | `tools.jackson.dataformat:jackson-dataformat-yaml` (Jackson 3, BOM-managed) | Parsed to a Jackson tree only (§8.4). |
| Tests | `spring-boot-starter-webflux-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-security-test`, `spring-boot-testcontainers`, Testcontainers PostgreSQL module (BOM-managed coordinates, M1-V8) | Docker is required to run integration tests. |
| Database server | PostgreSQL **17.x** Docker image, pinned by minor tag at M1: **`postgres:17.11`** | Same tag for Compose and Testcontainers (OD-4). |

**Starters (complete list for the baseline):**
- `spring-boot-starter-webflux`
- `spring-boot-starter-security`
- `spring-boot-starter-security-oauth2-resource-server`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` (mandatory)
- `org.postgresql:postgresql`
- Jackson 3 `jackson-dataformat-yaml`
- `io.micrometer:context-propagation` (BOM-managed; not transitive in 4.1.2; required for ADR-003 §7, found at M1-V10)
- `json-schema-validator` 3.0.x
- Test starters listed above

**`spring-boot-starter-web` / `spring-boot-starter-webmvc` must not be on the classpath** — with Spring MVC present, Spring Boot selects MVC on a servlet container instead of WebFlux on Netty.

#### M1 verification checklist (nothing below is claimed as tested in M0/M0.1)

| ID | Verify at M1 | Pass condition / fallback |
|---|---|---|
| M1-V1 | Resolve `spring-boot-starter-parent:4.1.2` from Maven Central; run `mvn help:effective-pom`, `mvn dependency:tree`, `mvn enforcer:enforce`. | Record every resolved version in `docs/dependency-versions.md`. Spring Framework ≥ 7.0.10. |
| M1-V2 | `org.flywaydb:flyway-database-postgresql` resolves at the same version as the BOM-managed Flyway core (no explicit version in the POM), and Flyway applies all available migrations against the selected PostgreSQL 17 image (OD-4 tag) in Testcontainers and Docker Compose. | Both resolution and migration succeed; record the versions and image tag. If the module is not BOM-managed in 4.1.2, pin it to exactly the managed Flyway version and record why. The dependency itself is not optional. |
| M1-V3 | Starter name `spring-boot-starter-security-oauth2-resource-server` resolves in 4.1.2; reactive HS256 decoding (`NimbusReactiveJwtDecoder` with a secret key) and encoding (`NimbusJwtEncoder`) APIs in Spring Security 7.1. | Auth integration tests pass with HS256 tokens. |
| M1-V4 | Exact `json-schema-validator` 3.0.x patch; its Jackson 3 dependency converges with the Boot Jackson BOM. | `dependencyConvergence` passes; timeline schema tests pass. If it does not converge, stop and report — do not fall back to 2.x. |
| M1-V5 | Hibernate 7 JSON mapping for JSONB columns uses Jackson 3. | A JSONB round-trip test passes. If Hibernate's default JSON format mapper does not use Jackson 3, configure a Jackson 3 format mapper explicitly (`hibernate.type.json_format_mapper`); do not add Jackson 2. |
| M1-V6 | No Jackson 2 `com.fasterxml.jackson.core:jackson-databind` arrives transitively. Jackson 3 continues to use the `com.fasterxml.jackson.annotation` annotations artifact; confirm. | Enforcer `bannedDependencies` rule for `jackson-databind` 2.x and `spring-boot-jackson2`; any unavoidable exception documented with its reason. |
| M1-V7 | Jackson 3 YAML parsing with SnakeYAML `LoaderOptions` limits and `StreamReadConstraints` via Jackson 3 builder APIs. | §8.4 parser tests (aliases, nesting, size, duplicate keys) pass. |
| M1-V8 | Testcontainers version and PostgreSQL module coordinates managed by 4.1.2 (artifact names may differ from Testcontainers 1.x); `@ServiceConnection` for PostgreSQL. | Context-load test on Testcontainers PostgreSQL 17 passes. |
| M1-V9 | Modular test starters (`webflux-test`, `data-jpa-test`, `security-test`) provide `WebTestClient`, JPA test slices, `reactor-test` and security test support. | Add `reactor-test` explicitly only if not transitive. |
| M1-V10 | Reactor ↔ Micrometer context propagation configuration in Boot 4.1 (`spring.reactor.context-propagation=auto` or its replacement). | MDC propagation test across Netty → `db-*` thread hops passes. |
| M1-V11 | PostgreSQL 17 minor supported by the managed Flyway and JDBC driver versions. | Migrations and integration tests pass. |
| M1-V12 | Default of `spring.datasource.connection-fetch` (expected: eager). | Keep eager; the blocking-boundary test (ADR-003 §10) still detects connection acquisition off `db-*` threads. |
| M1-V13 | Structured logging and Actuator health groups (liveness/readiness, custom `realtime` indicator) under Boot 4.1 module layout. | Health and logging tests pass. |

**Support window:** published Spring support calendars list OSS support for 4.1.x until 2027-07. Confirm on the official Spring support page at M1 and record it in the README.

### ADR-003 — WebFlux with blocking JPA: execution rules

**Decision:** keep Spring WebFlux for REST and WebSockets [PRD §8–9] and Spring Data JPA/Hibernate for persistence [PRD §8–9], with a strict boundary. [DD]

1. **No blocking on Netty threads.** No JDBC/JPA call, `Thread.sleep`, blocking file I/O, or blocking HTTP call may run on a `reactor-http-nio-*` thread.
2. **One dedicated bounded database executor** (`db-*` threads), exposed as a Reactor `Scheduler`. Thread count **equals** `spring.datasource.hikari.maximum-pool-size` (default 10); startup fails if they differ. Bounded queue (default 500); rejection → `503 SERVICE_BUSY`. Hikari `connection-timeout` 2 s. Do not use the default `boundedElastic` scheduler for JDBC (its thread cap far exceeds the pool and would park threads on connection acquisition).
3. **Adapter pattern.** Controllers/handlers call a reactive façade, which wraps a blocking application service exactly once: `Mono.fromCallable(() -> service.op(principal, cmd)).subscribeOn(dbScheduler)`. `subscribeOn`/`publishOn` calls are not scattered through business logic.
4. **Transactions.** `@Transactional` (or `TransactionTemplate`) is used only on blocking methods that return plain values/DTOs. **No method that relies on a JPA transaction returns `Mono`/`Flux`.** (With only a `JpaTransactionManager`, Spring would try to apply reactive transaction semantics to a reactive return type and fail.) No transaction spans thread hops.
   - **Commit-then-reject rule.** An unchecked exception thrown out of a transactional method rolls the transaction back. When a request must be *rejected* but its side effects must still be *kept* (for example refresh-token replay revoking a token family, §8.2), the transactional method **returns an outcome value** (e.g. a sealed `RefreshOutcome`) instead of throwing. The transaction commits, and only then does the web adapter map the outcome to the error response. `noRollbackFor` is not used for this purpose.
5. **Explicit principal.** The `AuthenticatedUser` (userId, systemRole, token expiry) is resolved from the reactive security context at the edge and passed as a method argument into every blocking service. Services never read `SecurityContextHolder`, which is empty on `db-*` threads.
6. **DTO mapping inside the transaction.** Entities never leave the transactional method; there is no open-session-in-view in WebFlux. `spring.jpa.open-in-view=false`. Lazy associations are fetched explicitly (fetch joins / projections).
7. **Context propagation.** Logging MDC (correlationId, sessionCorrelationId, watchSpaceId, userId) propagates across Reactor and executor hops via Micrometer context propagation (Boot 4.1 configuration confirmed at M1-V10) and a decorating executor that copies MDC.
8. **Timers never mutate state directly.** Trivia ticks, vote deadlines, host-grace expiry, idle auto-end and end-of-title detection run on one shared scheduler thread that only **submits commands** to the affected room lane.
9. **LLM calls are non-blocking.** The provider adapter uses `WebClient` (reactive) with a concurrency limiter and timeout; it never runs inside the lane (§4.8).
10. **Test the boundary (M1).** An integration test wraps the `DataSource` and fails if a connection is acquired on any thread not named `db-*`. (BlockHound is deferred — O3.)

### ADR-004 — PostgreSQL is the system of record

Relational tables with real keys, composite foreign keys and CHECK constraints for users, rooms, membership, ledger, chat, AI Q&A, votes, interactions and analytics. JSONB only for type-specific timeline payloads, ledger payloads and validation errors. [PRD §7.2.2 guidance; DD for details]

No MongoDB, Redis, Kafka, vector database or separate search engine in the initial build; none is needed for the local/private acceptance criteria. Any addition requires an approved ADR first.

### ADR-005 — Room runtime kernel: one serialized mutation lane per room

**Decision:** the `watchspace` module owns, per room: the mutation lane, the in-memory authoritative state, sequence and `stateVersion` allocation, and the `room_events` ledger. [DD; resolves C1]

**Lane model.**

- A registry maps `watchSpaceId → RoomLane`. Lanes are created lazily on the first mutation for **any** room status (including `SCHEDULED`), and evicted after 10 minutes of inactivity when the room has no connected sessions and no pending timers.
- Each lane processes **one mutation at a time**, in arrival order, from a bounded queue (default 256; overflow → `503 ROOM_BUSY`). Many lanes share the database executor; a lane never holds a thread while idle.
- **Every** durable room mutation goes through the lane, whether it arrives by REST (create side effects, join, leave, start, end, settings, lock, invite rotation, mute, remove, host transfer, pin, on-demand trivia, vote open/ballot/cancel, host variant selection, AI question/answer publication) or by WebSocket (playback, chat, AI ask, ballot), or from a timer.
- Readers (snapshot, sync reports, presence) read an **immutable** `RoomState` published through an atomic reference after each successful mutation. They never block the lane.

**Mutation contract** (`RoomMutation<R>`, defined in `watchspace`, implemented by any module):

1. **Validate** against the current in-memory `RoomState` and the explicit principal (authorization included). Pure; no I/O.
2. **Decide**: compute the next state and the list of ledger events. Pure.
3. **Persist and commit** on the DB executor in one transaction:
   - Write the mutation's own rows (e.g. a chat message, a ballot).
   - Append its `room_events`, numbered `lastSequenceNo + 1 … + n`.
   - Update `watch_spaces` (snapshot columns, `state_version`, `last_sequence_no`) **with an optimistic guard**: `… WHERE id = :id AND last_sequence_no = :expected`.
   - For joins, also `SELECT … FOR UPDATE` the `watch_spaces` row and re-check capacity, lock, removal and privacy (R8).
4. **Apply**: only after a successful commit, publish the new immutable `RoomState`.
5. **Broadcast**: hand the committed server events to `RoomBroadcaster` in `sequenceNo` order.
6. **Complete** the caller's `Mono` (REST response or WebSocket ack).

**Failure handling.**

| Failure | Outcome |
|---|---|
| Validation fails | Typed error to the caller; no write, no state change, no broadcast. |
| Transaction fails (constraint, I/O) | Rolled back; in-memory state untouched; nothing broadcast; caller receives an error. A failed DB write is never reported as success. |
| Optimistic guard updates zero rows (an out-of-lane writer or stale lane) | Roll back; mark lane stale; re-hydrate state from the DB (`last_sequence_no`, `state_version`, snapshot, memberships, moderation, open votes); reject the command with `409 CONFLICT_RETRY`. |
| Broadcast fails for a session | Does not roll back. That session is closed; the client recovers through reconnect, snapshot and history. |
| Process crash between commit and broadcast | DB is authoritative; recovery (§4.13) re-hydrates and clients resync from the snapshot. |

**Sequence numbers** are never computed with `MAX(sequence_no)+1` per mutation. The lane hydrates `last_sequence_no` once from `watch_spaces` and advances it under the guard.

**Canonical mutation instant (deterministic timestamps).**
- Each lane mutation reads the injected `java.time.Clock` **exactly once**, at the start of its *Decide* step, and truncates the result to **microseconds** (`Instant.truncatedTo(ChronoUnit.MICROS)`), PostgreSQL's `timestamptz` precision. It is then clamped so it is never earlier than the room's previous `mutationInstant`, which makes instants non-decreasing in `sequence_no` order within a room even if the wall clock steps backwards. This value is the mutation's `mutationInstant`.
- Every timestamp written by that mutation uses this same value: every `room_events.occurred_at` it appends, and any room/membership/vote column it sets (`started_at`, `ended_at`, `host_disconnected_at`, `joined_at`, `left_at`, `closed_at`, `applied_at`, `playback_updated_at`, …). The in-memory `RoomState` carries the same value. Lane mutations also set `created_at`-style columns explicitly rather than relying on their `now()` defaults. No timestamp is taken from the database clock or read twice from the Java clock. (Defaults remain only for writes outside the lane, such as user registration.)
- When a lane hydrates, the previous instant is the `occurred_at` of the room's event at `sequence_no = last_sequence_no` (a single unique-key lookup).
- `room_events.occurred_at` therefore has **no database default**; the application must always supply it (enforced by `NOT NULL` without `DEFAULT`).
- Consequences relied on by analytics (§9.4) and interaction derivation (§4.12):
  - `watch_spaces.started_at` = `occurred_at` of `ROOM_STARTED`.
  - `watch_spaces.ended_at` = `occurred_at` of `ROOM_ENDED`.
  - `watch_space_participants.joined_at`/`left_at` = `occurred_at` of the corresponding `PARTICIPANT_JOINED` / `PARTICIPANT_LEFT` / `PARTICIPANT_REMOVED` / `ROOM_ENDED` event.
- Ledger ordering is always by `sequence_no`, never by `occurred_at`, because several events of one mutation share an instant.
- **`ROOM_ENDED` is the last ledger event of a room.** After it commits, the lane rejects any further append for that room (late AI answers and timers append nothing, §4.8).

**Ephemeral traffic** — typing, reactions, sync ping/report, connection quality — does not enter the lane or the ledger. It reads the published state and goes straight to `RoomBroadcaster` (rate-limited).

**Post-commit listeners.** `RoomLifecycleListener` callbacks (analytics snapshot, interaction derivation, cancelling open votes on end) run after commit as separate idempotent transactions submitted to the same lane. If one fails it is retried; recovery (§4.13) re-runs any that are missing.

**MVP limitation:** room runtime state is single-process. Many concurrent rooms are supported in one process, but not horizontal scaling across instances; that would require distributed room ownership and pub/sub and a new ADR. No code hard-codes a single room. [PRD §10.2]

### ADR-006 — Structured timeline retrieval (no vector search)

Retrieval runs over the room-pinned, author-written timeline version using time overlap, a recent window and keyword matching over **prior** entries (§4.8). The LLM only phrases answers from retrieved entries. No vector database. [DD consistent with PRD §6.2 and §7.2.2 guidance]

### ADR-007 — Only pre-authored, approved variations

The system only **selects** among approved `variation_options` and seeded subtitle tracks. Variant suggestions are produced by deterministic eligibility rules, not by a model. No model output can create or modify a subtitle line, asset or narrative branch. [PRD §4.2, §5.3, §14.2]

### ADR-008 — Schema management

`DATABASE_SCHEMA.sql` is the approved **target state**, not a migration. Flyway versioned migrations are written **per feature slice** (V1 auth … V7 recommendations/analytics, listed at the end of `DATABASE_SCHEMA.sql`). Applied together they must equal the target state. `spring.jpa.hibernate.ddl-auto` is `none` at runtime and `validate` in integration tests. Seed data is loaded by a separate, profile-gated seed step.

---

## 3. Component view

```mermaid
flowchart TB
    C[Postman / JUnit HTTP tests / JUnit scripted WebSocket clients]
    C -->|REST JSON| API[WebFlux REST controllers]
    C -->|WebSocket JSON| WS[realtime: WebSocket handler + session registry]
    API --> SEC[Spring Security reactive chain: JWT]
    WS --> SEC
    API --> SVC[Module application services]
    WS --> SVC
    SVC --> LANE[watchspace: per-room mutation lane + immutable RoomState + ledger]
    LANE -->|RoomTickHandler| AI[ai: trivia, Q&A]
    LANE -->|RoomTickHandler| VAR[variations: votes, subtitles]
    LANE -->|RoomBroadcaster| WS
    SVC --> DBX[db executor = Hikari pool size]
    LANE --> DBX
    DBX --> DB[(PostgreSQL 17)]
    AI -->|WebClient, bounded, timeout| LLM[Configured LLM provider]
    VAR --> ASSET[Local asset directory]
```

One application process, one PostgreSQL instance, one local asset volume.

---

## 4. Domain rules and state machines

### 4.1 Room lifecycle and playback state machine [PRD §6.1; DD for details]

**Room lifecycle**

| From | Trigger | To | Notes |
|---|---|---|---|
| — | `POST /watch-spaces` | `SCHEDULED` | Host gets an active membership; ledger `ROOM_CREATED`, `PARTICIPANT_JOINED`. |
| `SCHEDULED` | host `start` | `LIVE` | Requires a `CURRENT` timeline (`409 NO_CURRENT_TIMELINE`). Pins the timeline version and ACTIVE subtitle tracks; assigns `session_correlation_id`; playback `PAUSED` at 0. |
| `LIVE` | host's last socket closes | `HOST_DISCONNECTED` | Playback forced to `PAUSED` at the estimated position (§4.3). |
| `HOST_DISCONNECTED` | host reconnects within grace, or promotion | `LIVE` | Playback stays `PAUSED` until the (new) host plays. |
| `SCHEDULED` / `LIVE` / `HOST_DISCONNECTED` | host `end` | `ENDED` (`HOST_ENDED`) | Closes memberships (`ROOM_ENDED`), cancels an open vote, triggers analytics and interactions. |
| `LIVE` / `HOST_DISCONNECTED` | no connected participant for `room.idle-timeout` (default 15 min) | `ENDED` (`IDLE_TIMEOUT`) | §4.13. |
| `SCHEDULED` | not started within `room.unstarted-expiry` (default 24 h after `scheduled_at`, else `created_at`) | `ENDED` (`UNSTARTED_EXPIRED`) | §4.13. |

`ENDED` is terminal; all mutations return `409 ROOM_ENDED` and sockets close with 4410.

**Playback** (only meaningful while `LIVE`; persisted states are `PLAYING`, `PAUSED`, `BUFFERING`)

| Command (host only) | From | To | Clock effect | Ledger |
|---|---|---|---|---|
| `PLAY` | `PAUSED`, `BUFFERING` | `PLAYING` | rebase at current position | `PLAYBACK_PLAY` |
| `PLAY` | `PLAYING` | `PLAYING` | none — idempotent no-op, acked, no event | — |
| `PAUSE` | `PLAYING`, `BUFFERING` | `PAUSED` | freeze at estimated position | `PLAYBACK_PAUSE` |
| `PAUSE` | `PAUSED` | `PAUSED` | none — idempotent no-op | — |
| `SEEK{positionMs}` | any | **prior state** (`BUFFERING` resolves to `PLAYING`) | rebase at target | `PLAYBACK_SEEK` |
| `BUFFERING{true}` | `PLAYING` | `BUFFERING` | freeze | `PLAYBACK_BUFFERING_STARTED` |
| `BUFFERING{false}` | `BUFFERING` | `PLAYING` | rebase | `PLAYBACK_BUFFERING_ENDED` |
| `BUFFERING{*}` | `PAUSED` | `PAUSED` | ignored, acked | — |
| system: end of title | `PLAYING` with estimated position ≥ duration | `PAUSED` at `durationMs` | freeze | `PLAYBACK_PAUSE` (reason `END_OF_TITLE`) |
| system: host disconnect | `PLAYING`/`BUFFERING` | `PAUSED` | freeze | `PLAYBACK_PAUSE` (reason `HOST_DISCONNECTED`) |

- `SEEK` target must satisfy `0 ≤ positionMs ≤ durationMs` (else `400 POSITION_OUT_OF_RANGE`). `SEEKING` is a transient concept that is never persisted or broadcast as a resting state. The broadcast carries `action: "SEEK"` so the PRD's `seek` semantics are preserved (§14).
- Playback commands in `SCHEDULED` or `HOST_DISCONNECTED` → `409 ROOM_NOT_LIVE`. From a non-host → `403 NOT_ROOM_HOST`.
- Every accepted state change increments `stateVersion` by 1. Each broadcast `room.playback.update` carries `stateVersion` and `sequenceNo`.
- Non-host clients only *report* positions (telemetry); they can never change the authoritative clock. Viewer buffering is local and never pauses the room.

### 4.2 Join, invitations, capacity, lock, moderation, quick-rejoin [PRD §6.1, §6.3; DD for details]

**Invite codes (I21).**

- 10 characters of Crockford Base32 from `SecureRandom` (≈50 bits), displayed as `XXXXX-XXXXX`.
- Normalized (upper-case, hyphen removed) and stored only as `HMAC-SHA256(INVITE_CODE_PEPPER, code)` in hex. The pepper is an environment secret of at least 32 bytes.
- The plaintext code is returned **only** by create and by rotation.
- `POST /watch-spaces/{id}/invite-code` (host only) issues a new code and invalidates the old one immediately. Existing members are unaffected. Ledger: `INVITE_CODE_ROTATED` (payload has no code).
- Hash collisions on insert are retried with a new code.

**Privacy (I21).**

- `INVITE_ONLY`: joining requires the invite code. Non-members get `404` for every room endpoint, so the room's existence is not revealed.
- `PUBLIC_LINK`: any authenticated user who has the room link (the room UUID, 122 random bits) may join without a code. Non-members may `GET` a limited preview (name, title, status, connected count, capacity, locked).
- There is no public room directory or search in either mode.
- Both modes require authentication and are subject to lock, capacity and removal rules.

**Join rules** (`POST /watch-spaces/{id}/join` or `POST /watch-spaces/join` with only `{inviteCode}`), executed in the lane transaction with a row lock (R8):

1. Room status is `SCHEDULED`, `LIVE` or `HOST_DISCONNECTED` (else `409 ROOM_ENDED`).
2. `watch_space_moderation.removed_at` is null for this user (else `403 REMOVED_FROM_ROOM`). **A removed user can never rejoin that room.**
3. If the user already has an active membership, the call is idempotent: return the membership, no ledger event.
4. **Quick-rejoin:** a user with any previous membership in this room (and not removed) may rejoin **without a code, and even when the room is locked**. Lock blocks only users who were never members.
5. A new user must satisfy privacy (code for `INVITE_ONLY`, else `409 INVITE_CODE_INVALID`) and `is_locked = false` (else `409 ROOM_LOCKED`).
6. Active memberships (host included) must be `< max_participants` (else `409 ROOM_FULL`).
7. Create membership; ledger `PARTICIPANT_JOINED`; broadcast presence.

**Leave and reconnect.**

- Explicit `POST /leave` closes the membership (`LEFT`, ledger `PARTICIPANT_LEFT`).
- A socket drop starts a per-user grace timer (`room.reconnect-grace`, default 30 s). Reconnecting within it resumes the same membership, emits no `PARTICIPANT_JOINED`, and broadcasts presence `RECONNECTED` only.
- Grace expiry closes the membership (`GRACE_EXPIRED`, ledger `PARTICIPANT_LEFT`).

**Moderation (I10)** — host only, persisted in `watch_space_moderation`:

- **Mute:** `is_muted` survives reconnects, leave and rejoin. Muted users get `403 CHAT_MUTED` on chat; they may still use AI and vote. Ledger `PARTICIPANT_MUTE_CHANGED`.
- **Remove:** sets `removed_at`, closes the membership (`REMOVED`), closes the user's socket with 4411, ledger `PARTICIPANT_REMOVED`. The host cannot remove or mute themself.
- **Lock/unlock:** `POST /lock {locked}`; ledger `ROOM_LOCK_CHANGED`.
- **Room-wide chat mute:** a setting (§4.11).

**Host transfer.** `POST /host-transfer {newHostUserId}`: the target must be an active, currently connected member. Updates `watch_spaces.host_user_id` (the single source of truth) in the lane transaction; ledger `HOST_TRANSFERRED`; broadcast `room.host.transferred`.

**Room creation.** Any authenticated user (`USER` or `ADMIN`) may create a room for a `PUBLISHED` title. At most 5 non-ended rooms may be hosted per user (`409 HOST_ROOM_LIMIT`). [DD]

`participantCount` = number of distinct users with at least one connected socket. Membership history is persisted separately. Capacity counts active memberships, not sockets.

### 4.3 Host disconnect and deterministic promotion [PRD §6.1 AC; DD]

1. The host's last socket closes while `LIVE` → in one lane mutation: playback `PAUSED` at the estimated position, status `HOST_DISCONNECTED`, `host_disconnected_at` set, ledger `PLAYBACK_PAUSE`(`HOST_DISCONNECTED`) + `HOST_DISCONNECTED`, broadcast `room.host.disconnected` with `graceEndsAtMs`.
2. Host reconnects within `room.host-grace` (default 30 s, configurable) → `LIVE`, ledger `HOST_RECONNECTED`, still paused.
3. Grace expires → choose the new host among members that are **active and currently connected**, excluding the old host. Order by the current membership's `joined_at` ascending, then `user_id` ascending. Update `host_user_id`; ledger `HOST_PROMOTED`; status `LIVE`; playback stays `PAUSED`; broadcast `room.host.transferred` (reason `PROMOTED`). The old host's membership follows the normal participant grace rule.
4. No connected candidate → remain `HOST_DISCONNECTED` and paused. The first member to connect afterwards is promoted immediately by the same rule. Idle auto-end (§4.13) eventually ends an abandoned room.

Every step is a lane mutation, so promotion is deterministic and testable with a controllable clock.

### 4.4 Playback clock, drift correction, connection quality [PRD §5.2, §6.1, §10.2; DD]

**Clock.** Runtime state: `basePositionMs`, `baseServerTimeMs` (monotonic server clock), `state`.

`estimatedPositionMs = state == PLAYING ? min(durationMs, base + (now − baseServerTime)) : base`

**Time-sync handshake.** Clients send `room.sync.ping {clientSentAtMs}` (the harness sends ≥ 3 before applying the snapshot). The server replies `room.sync.pong {clientSentAtMs, serverReceivedAtMs, serverSentAtMs}`. The client estimates offset and RTT NTP-style. `room.snapshot` and every `room.playback.update` include `serverTimeMs`, so the client computes `position = positionMs + (nowServer − serverTimeMs)` when `PLAYING`.

**Drift reports.** `room.playback.report {positionMs, playbackRate, clientSentAtMs}`, at most 4/s (recommended 1/s). The server converts the client timestamp using the session's offset estimate, computes `driftMs = reported − expected` and replies privately with `room.sync.correction {expectedPositionMs, driftMs, correctionMode, stateVersion}`.

| abs(drift) | `correctionMode` | Client action (documented contract) |
|---|---|---|
| < 100 ms | `NONE` | — |
| 100–250 ms | `RATE_ADJUST` (or `MICRO_SEEK` if rate control is unavailable) | Gentle nudge. [PRD: micro-seek, not hard seek] |
| > 250 ms | `RESYNC` | Seek to expected position. |

Thresholds are configuration defaults to be tuned against measurements; meeting a target is never claimed without measured data.

**Connection quality (C5).** Per session, ephemeral and never persisted:
- `GOOD`: median RTT of the last 5 pongs < 150 ms **and** last abs(drift) < 100 ms.
- `FAIR`: RTT < 400 ms and abs(drift) < 250 ms.
- `POOR`: otherwise.
- `UNKNOWN`: before data.

Included in the snapshot participant list and broadcast as `room.presence.update` (`action: QUALITY`) only when the class changes, at most once per 5 s per user. Thresholds are configurable defaults. [DD]

**Measurement caveat:** scripted clients simulate a player. Measured drift reflects protocol, clock-estimation and server accuracy, not real video decoding. Reports must say so.

### 4.5 Ordering and idempotency

- Every durable server event has a room-local, gap-free, strictly increasing `sequenceNo` assigned by the lane (ADR-005). All clients receive committed events in `sequenceNo` order.
- Every mutating client command carries a `requestId` (UUID). The ledger stores `idempotency_key = "cmd:<userId>:<requestId>"`. A retried command with the same key returns the original outcome without re-applying it. Scoping by user prevents cross-user collisions.
- System-originated idempotency keys: `trivia:<timelineEventId>`, `vote-close:<voteId>`, `promote:<watchSpaceId>:<hostDisconnectedAt>`.
- AI questions are deduplicated by `(watchSpaceId, userId, clientRequestId)`.

### 4.6 Chat and the room event ledger [PRD §6.1, §9.4; DD]

- **Chat:** validate (§8.3) → lane → in one transaction insert `room_events` (`CHAT_MESSAGE_POSTED`, payload `{messageId}`) and `chat_messages` → commit → broadcast `room.chat.message`. A delivered message is always durable.
- Messages are written individually for now. Batching (PRD §9.4 makes it conditional on volume) is deferred until load measurements justify it (O9).
- **History:** `GET /watch-spaces/{id}/chat?beforeSequence=&afterSequence=&limit=` (limit ≤ 100), ordered by the joined `room_events.sequence_no`. On (re)connect the server sends the snapshot (with `lastSequenceNo`) followed by `room.chat.history` (last 50). Clients fill gaps with `afterSequence`.
- **Typing and reactions:** ephemeral fan-out, rate-limited, never persisted. Reactions use an allow-list of 12 emoji.

**Event-type catalog (I11)** — closed set, enforced by a CHECK constraint. Adding a type requires a design change, a migration and an updated reconciliation test.

| Group | Types |
|---|---|
| Room | `ROOM_CREATED`, `ROOM_STARTED`, `ROOM_ENDED`, `ROOM_SETTINGS_UPDATED`, `ROOM_LOCK_CHANGED`, `INVITE_CODE_ROTATED`, `RUNTIME_RECOVERED` |
| Membership | `PARTICIPANT_JOINED`, `PARTICIPANT_LEFT`, `PARTICIPANT_REMOVED`, `PARTICIPANT_MUTE_CHANGED` |
| Host | `HOST_DISCONNECTED`, `HOST_RECONNECTED`, `HOST_TRANSFERRED`, `HOST_PROMOTED` |
| Playback | `PLAYBACK_PLAY`, `PLAYBACK_PAUSE`, `PLAYBACK_SEEK`, `PLAYBACK_BUFFERING_STARTED`, `PLAYBACK_BUFFERING_ENDED` |
| Chat / AI | `CHAT_MESSAGE_POSTED`, `AI_QUESTION_CREATED`, `AI_ANSWER_PUBLISHED`, `AI_FACT_PINNED`, `AI_FACT_UNPINNED`, `TRIVIA_SURFACED` |
| Variations | `VOTE_OPENED`, `VOTE_CLOSED`, `VOTE_CANCELLED`, `VARIATION_APPLIED` |

Payloads hold references and parameters only (IDs, positions, reasons), never chat bodies or secrets. `playback_position_ms` records the authoritative position at the event.

### 4.7 Timeline ingestion, trivia and on-demand trivia [PRD §6.2, §6.3; DD]

**Upload lifecycle (I8).**

1. `POST /admin/titles/{titleId}/timeline-versions` accepts `multipart/form-data` (part `file`), `application/json` or `application/yaml`, max 2 MiB.
2. The server stores the raw document as a `DRAFT` version and **validates immediately**:
   - Parse safely (§8.4).
   - Validate against the checked-in JSON Schema `schemaVersion = 1` (`src/main/resources/timeline/schema-v1.json`).
   - Apply cross-reference rules R1 in `DATABASE_SCHEMA.sql`.
3. Valid → `validation_status = VALID`; events and options are materialized; returns `201` with the version.
4. Invalid → `validation_status = INVALID` with field-level errors; returns `422 TIMELINE_INVALID` with `details[] {path, code, message}`. The active timeline is untouched.
5. `POST /admin/timeline-versions/{id}/validate` re-runs validation on a `DRAFT` (e.g. after subtitle seed changes).
6. `POST /admin/timeline-versions/{id}/publish` requires `VALID`; in one transaction it sets the previous `CURRENT` to `SUPERSEDED` and this version to `CURRENT`.
7. Rooms already started keep their pinned version; admin publishing never mutates a live room's timeline.

**Contract (§5.3)** includes `schemaVersion`, `titleId` (must equal the URL), `durationMs` (must equal the title's), and `events[]`. String fields are plain text (§8.3).

**Proactive trivia (C3).** A shared 250 ms tick submits a `Tick` mutation to each room lane where status is `LIVE`, playback is `PLAYING` and `trivia_enabled = true`.

- `ai`'s `RoomTickHandler` finds `TRIVIA` events with `watermark < start_ms ≤ estimatedPosition` from the pinned version (served from the per-version cache).
- For each one not yet surfaced (idempotency key `trivia:<eventId>`), it appends `TRIVIA_SURFACED` and broadcasts `room.ai.trivia`.
- Trivia text is authored metadata delivered verbatim; no LLM is involved, so no model latency.
- The watermark always advances to the current position.
- **Seek** resets the watermark to the target, so skipped markers are not delivered and rewinding never re-delivers (idempotency).
- Markers crossed while `trivia_enabled = false` are not back-filled when it is re-enabled.
- Latency budget: ≤ 250 ms tick + lane + commit + fan-out, target < 1 s for ≥ 95 % of naturally crossed markers [PRD §6.2 AC], measured.

**On-demand trivia (I2).** `POST /watch-spaces/{id}/trivia/next` (host only, `LIVE`) surfaces the most recent not-yet-surfaced `TRIVIA` event with `start_ms ≤ estimatedPosition` (never a future one) as `TRIVIA_SURFACED` (payload `trigger: ON_DEMAND`). Works even when `trivia_enabled = false`, because it is an explicit host action. None available → `409 NO_TRIVIA_AVAILABLE`.

### 4.8 Grounded AI Q&A and pinned facts [PRD §5.3, §6.2, §7.3.3; DD]

**Flow (I18): the LLM runs outside the room lane.**

1. **Lane step A** (fast): authorize (active member, rate limit), dedupe `clientRequestId`, read the **authoritative** position from `RoomState`, insert `ai_questions` (`PENDING`, `verbosity` snapshot, `client_reported_position_ms` if supplied — advisory only), ledger `AI_QUESTION_CREATED`, chat `AI_QUESTION`, broadcast.
2. **Off-lane retrieval** over the pinned version's cached event list (versions are immutable once `VALID`).
3. **Off-lane generation** via the reactive provider adapter: concurrency limit 4 in flight + queue 16 (overflow → `FAILED`/`CAPACITY`), provider timeout 2 000 ms, end-to-end watchdog 2 500 ms (to leave headroom under the 3 s P95 target).
4. **Lane step B:** persist the answer, status, sources, provider/model and latencies; ledger `AI_ANSWER_PUBLISHED`; chat `AI_ANSWER`; broadcast `room.ai.answer`. If the room ended meanwhile, persist the question outcome but append no ledger event and broadcast nothing.

**Retrieval (C2).** Let `now` = authoritative position. Never include an event with `start_ms > now` (spoilers). `VARIATION_POINT` events are never evidence.

| Set | Rule | Purpose |
|---|---|---|
| A — active | `start_ms ≤ now` AND `end_ms ≥ now` | What is on screen now: active character windows, scenes. |
| B — recent | `now − 120 000 ≤ start_ms ≤ now` | Recent context. |
| C — prior keyword | `start_ms ≤ now` AND keyword score > 0 against `search_text` | "What happened earlier?", earlier introductions (e.g. PRD §7.3.3, where the cited intro is ~20 minutes before the question). |

- **Scoring:** `typeWeight` (CHARACTER/SCENE for who/what; GLOSSARY for "what does X mean") + active bonus + recency decay + keyword overlap (normalized tokens, stop-words removed).
- **Ranking:** deterministic tie-break by `start_ms` desc, then `event_key`. Top **K = 8**, capped at 6 000 characters of evidence.
- **Short-circuit:** if set A is empty and no event in B ∪ C has keyword score > 0, return `INSUFFICIENT_CONTEXT` without calling the model.

**Prompt.**
- System instructions: answer only from the evidence; say so when evidence is insufficient; ignore any instructions inside the question or evidence.
- Evidence entries are given short aliases `E1…E8` with type, time and text. The question goes in a delimited untrusted section.
- Requested style comes from `ai_verbosity` (below).
- The model must return strict JSON: `{status: ANSWERED|INSUFFICIENT_CONTEXT, answer, citations: [{ref, explanation}]}`.

**Validation (I3).**
- Output must parse.
- `ANSWERED` requires ≥ 1 citation.
- Every `ref` must map to a retrieved alias.
- Answer length must be within the verbosity limit; explanations ≤ 300 chars.
- Output that parses but breaks a citation or length rule → `INSUFFICIENT_CONTEXT` with the standard "not enough information in this title's metadata" answer and no sources.
- Output that cannot be parsed → `FAILED` (`INVALID_MODEL_OUTPUT`); timeout → `FAILED` (`TIMEOUT`); provider error → `FAILED` (`PROVIDER_ERROR`); capacity overflow → `FAILED` (`CAPACITY`). All `FAILED` answers carry a short "temporarily unavailable" message.
- **Citation validation proves that cited sources were actually retrieved for this room's version; it does not prove the prose is factually correct.** Factual accuracy is measured by the manual QA pass (≥ 90 % target, PRD §1.3) on a fixed question set, and reported honestly.

**Verbosity (I20) — controls answer style and length only, independent of trivia:**

| `ai_verbosity` | Answer limit | Style |
|---|---|---|
| `MINIMAL` | 1 sentence, ≤ 40 words | Terse fact. |
| `NORMAL` | ≤ 3 sentences, ≤ 90 words | Neutral. |
| `CHATTY` | ≤ 5 sentences, ≤ 160 words | Friendlier, may add one connecting detail from evidence. |

**Response** (REST and `room.ai.answer`): `questionId`, `status`, `answer`, `sourceEvents` (array of event UUIDs, PRD shape), `sources[]` (`eventId`, `eventKey`, `type`, `startMs`, `ts` seconds, `excerpt` server-built from authored text ≤ 500 chars, `explanation`), `positionMs`, `generatedAt`, `latencyMs`.

**Pinned facts (I2).** `POST /watch-spaces/{id}/ai/pins {aiQuestionId}` or `{timelineEventId}` (host only):
- Pins an `ANSWERED` answer from this room, or a `TRIVIA` event with `start_ms ≤ now` from the pinned version.
- One active pin per room; a new pin replaces the previous one (both changes in one mutation).
- `DELETE /watch-spaces/{id}/ai/pins/current` unpins.
- Ledger `AI_FACT_PINNED` / `AI_FACT_UNPINNED`; broadcast `room.ai.pinned` / `room.ai.unpinned`; included in the snapshot.

### 4.9 Subtitles and per-viewer locale alternates [PRD §6.2; DD; resolves C4, I9]

- **Tracks are seeded** for the MVP (no subtitle-upload API): at least the title's `default_locale` (e.g. `en`) and one alternate locale (e.g. `en-IN`). Assets live in the local asset directory with SHA-256 checksums.
- **Pinning (R3, R12):** at room start, the start transaction pins, for each locale, the single `ACTIVE` track **of the room's title** in `room_subtitle_tracks` — nothing else. The composite FK guarantees the pinned track's title and locale match the row; the `ACTIVE` check is made in the same transaction while reading the tracks. Later activations, archiving or new versions do not affect a started room.
- **Pinned tracks stay usable for the whole session (R13):** a track referenced by a non-`ENDED` room is never deleted (FK `RESTRICT`), and its asset file and cues are immutable once the track is `APPROVED` (any change is a new `version_number`). It may move to `ARCHIVED`, but the room keeps serving it until the room ends. Asset storage never deletes a file referenced by `subtitle_tracks`.
- **`SUBTITLE_TRACK_SWAP` options (R11):** the option's `subtitle_track_id` must reference a track of the **same title** as its timeline event (validated at timeline validation and publish), and the option's `locale` must equal the track's locale (enforced by a composite FK; `locale` is required for this option type). In a room, a swap option is **eligible** only if its track is the track pinned for that locale in that room. Ineligible options are excluded from votes, host selection and suggestions (`409 OPTION_NOT_ELIGIBLE`).
- **Required tests:**
  - Validation rejects a swap option whose track belongs to another title (field-level error).
  - The database rejects a swap option whose locale differs from its track's locale.
  - Room start pins exactly one `ACTIVE` track per locale for the room's title, and pins nothing from other titles or non-`ACTIVE` tracks.
  - After the pinned track is archived and a newer version activated mid-session, the room still serves the pinned track, its cues and asset until it ends; a newly started room pins the new version.
  - Voting or selecting a swap option whose track is not pinned in the room returns `409 OPTION_NOT_ELIGIBLE`.
- **Cue references:** a `SUBTITLE_TEXT` variation point names `payload.cueKey`. Cue keys are stable across a title's tracks and locales. Validation (R1) requires the cue key to exist in the `ACTIVE` track of `default_locale`.
- **Per-viewer alternates (separate from voting):**
  - Effective viewer locale = session override (`room.subtitle.locale`) if set, else `app_users.subtitle_locale`.
  - Track: the pinned track for that locale, else the pinned track for `default_locale`.
  - Alternates: every approved `SUBTITLE_TEXT` option of the pinned version whose `locale` equals the viewer locale (exact match, then same primary language), with `effectivePositionMs` (event `start_ms`) and `cueKey`.
  - Delivered **privately** in `room.snapshot.subtitles` and in `room.subtitle.variants` on connect, on `room.subtitle.locale`, and after `PATCH /me` changes the locale.
- **Precedence at a cue:** room-wide applied variation (vote or host selection) > per-viewer locale alternate > base track text. Group results therefore stay identical for everyone, as the vote AC requires.
- **No desync by construction:** locale alternates never enter the lane, never write the ledger, never change `stateVersion` and never emit `room.playback.update`. Tests assert exactly this. [PRD §6.2 AC]
- **REST:**
  - `GET /titles/{titleId}/subtitles?locale=` returns the ACTIVE track metadata.
  - `GET /watch-spaces/{id}/subtitles?locale=` (members) returns the pinned track plus the alternates for that locale.

### 4.10 Votes, host selection and variant suggestions [PRD §5.1, §5.2, §6.2; DD; resolves I19, I2]

**Open** (`POST /watch-spaces/{id}/votes {timelineEventId, durationSeconds}`, host only). All of these must hold:
- Room `LIVE`, `voting_enabled = true` (else `409 VOTING_DISABLED`).
- The event is a `VARIATION_POINT` of the pinned version with ≥ 2 approved options.
- No application exists for it in this room (`409 VARIATION_ALREADY_APPLIED`).
- No open vote exists for it (`409 VOTE_ALREADY_OPEN`).
- `durationSeconds` is within 10–120 (default 30).
- Point still ahead: `start_ms − estimatedPosition ≥ 2 000 ms` (else `409 VARIATION_POINT_PASSED`).
- If `PLAYING`, the vote must be able to close before the point: `durationMs + 2 000 ≤ start_ms − estimatedPosition` (else `409 VOTE_TOO_LATE`).

On success: ledger `VOTE_OPENED`; broadcast `room.variation.voteOpen`.

**Ballots.**
- `PUT /watch-spaces/{id}/votes/{voteId}/ballot {optionId}` or WS `room.variation.vote`.
- Any active member may vote (connection not required), one effective ballot per user. Re-voting replaces the ballot until close (primary key `(vote_id, user_id)`).
- The option must be approved and belong to the vote's point (composite FK).
- Ballots are **not** ledger events. The voter gets a private `room.ack`.
- **Counts are hidden until close:** no per-option or total counts are exposed while `OPEN`; `GET` returns status, options, `closesAt` and the caller's own choice only.

**Close** (lane timer at `closes_at` → `close_reason TIMER`; or early when a tick finds `estimatedPosition ≥ start_ms − 2 000` → `MARKER_REACHED`):
- Tally per option. Winner = most ballots; ties broken by lowest `sort_order`, then `option_key` ascending; zero ballots → the single default option.
- In one transaction: vote `APPLIED` with the winner, a `room_variation_applications` row (`source VOTE`), ledger `VOTE_CLOSED` (final counts in payload) + `VARIATION_APPLIED`.
- Then broadcast `room.variation.voteClosed` (final counts) and `room.variation.applied` (identical payload to everyone: `variationId`, `winningOptionId`, `effectivePositionMs`, `stateVersion`, `sequenceNo`).
- Clients switch at `effectivePositionMs`. Applying a variation never changes the playback clock. [PRD §6.2 AC]

**Cancel** → `CANCELLED`, ledger `VOTE_CANCELLED`, broadcast `room.variation.voteCancelled`:
- Host seeks to a position ≥ the point while `OPEN` (`SEEK_PASSED`), decided inside the seek mutation.
- Host cancels: `POST …/votes/{voteId}/cancel` (`HOST_CANCELLED`).
- Host disables voting (`HOST_CANCELLED`).
- Room ends (`ROOM_ENDED`).
- Recovery finds the point passed (`RECOVERY_POINT_PASSED`).

**Already applied variations stay applied** when the host later seeks backward; the application row is permanent for the room session.

**Host selection (I2):** `POST /watch-spaces/{id}/variations/{timelineEventId}/selection {optionId}` (host only, no open vote for the point, not already applied, option approved). Writes an application (`HOST_SELECTION`); ledger `VARIATION_APPLIED`; same `room.variation.applied` broadcast.

**Suggestions:** on start and on membership changes, `variations` computes *eligible* points deterministically: future `SUBTITLE_TEXT` points with an approved option matching the locale of ≥ 1 connected participant, plus any point not yet applied. It sends `room.variation.suggested` **to the host only**. The host may select an option or open a vote. No model is involved.

### 4.11 Room settings (C3, I20)

`PATCH /watch-spaces/{id}/settings` (host only, not `ENDED`) accepts any of:
- `triviaEnabled` (proactive trivia only)
- `votingEnabled` (disabling cancels an open vote)
- `aiVerbosity` (answer style/length only)
- `chatMuted` (room-wide)
- `maxParticipants` (2–50 and ≥ current active memberships, else `409`)

Ledger `ROOM_SETTINGS_UPDATED` (changed fields). Broadcast `room.settings.update` with the full settings object. Lock remains `POST /lock`.

### 4.12 Watch interactions derived from the ledger (I13)

Heartbeats are not persisted [PRD §9.4], so watch facts are reconstructed from the ledger:

1. **Membership intervals** for the user: `PARTICIPANT_JOINED` → `PARTICIPANT_LEFT` / `PARTICIPANT_REMOVED` / `ROOM_ENDED`.
2. **Playing intervals** in content time: from `PLAYBACK_*`, `HOST_DISCONNECTED` and `ROOM_ENDED` events (`occurred_at` + `playback_position_ms`). Each PLAYING segment maps wall time to the content range `[p, p + Δt]`, clamped to the duration.
3. Intersect the user's membership intervals with the playing segments, map to content ranges, and take their **union**. A rewatch does not double count; skipping does not inflate.
4. `watched_seconds = floor(union_ms / 1000)`; `completed = union_ms / duration_ms ≥ recommendations.completion-threshold` (default **0.90**, configurable).

- Events are processed in `sequence_no` order. Interval boundaries use each event's `occurred_at` (the canonical mutation instant, ADR-005), so the derivation is deterministic and repeatable from the ledger alone.
- Computed at the user's final leave (`is_final = false`) and finalized at room end (`is_final = true`), with `derived_through_sequence`. Idempotent upsert keyed `(user_id, watch_space_id)`.
- **Limitation (documented):** time inside a reconnect grace window counts as watched, because socket connectivity is not persisted.

### 4.13 Recovery, idle auto-end, graceful shutdown (I17)

**Startup recovery** runs before the WebSocket endpoint and readiness report `UP`:
- For each `LIVE` or `HOST_DISCONNECTED` room: hydrate the lane from the DB; force playback `PAUSED` at the persisted snapshot position; set status `HOST_DISCONNECTED` with `host_disconnected_at` = the recovery mutation's `mutationInstant` (the host is not connected after a restart); start host and participant grace timers from that instant; ledger `RUNTIME_RECOVERED` (+ `PLAYBACK_PAUSE` if it was playing).
- **Open votes:**
  - Point at or behind the recovered position → `CANCELLED` (`RECOVERY_POINT_PASSED`).
  - `closes_at` already passed but point still ahead → close and tally now.
  - Otherwise → re-arm the timer.
- Trivia watermark = recovered position (no back-fill).
- Ended rooms missing `watch_space_analytics` or final interactions have their post-commit listeners re-run.

**Idle auto-end:** a `LIVE`/`HOST_DISCONNECTED` room with no connected participant for `room.idle-timeout` (default 15 min) is ended with `IDLE_TIMEOUT`. A `SCHEDULED` room past `room.unstarted-expiry` is ended with `UNSTARTED_EXPIRED`. Both are lane mutations with full end-of-room processing.

**Graceful shutdown:**
- Readiness `OUT_OF_SERVICE`; new joins and new sockets → `503`.
- Each live room is paused via a lane mutation so the persisted snapshot is exact.
- Lanes drain (bounded wait 10 s); sockets close with 1001.

A hard crash loses only in-flight, uncommitted mutations; continuity is not guaranteed and this is documented.

---

## 5. Database design

`DATABASE_SCHEMA.sql` v0.4 is canonical. UUIDs are application-generated; timestamps are `timestamptz` UTC; positions are integer milliseconds; deletion is `RESTRICT` (lifecycle columns instead of hard deletes).

### 5.1 Tables

| Table | Owner | Key constraints |
|---|---|---|
| `app_users` | users | unique normalized email; `system_role ∈ {USER, ADMIN}` |
| `refresh_tokens` | auth | unique hash; `family_id`; revoke reason/replacement consistency |
| `titles`, `genres`, `title_genres` | catalog | unique slug; `default_locale` |
| `title_timeline_versions` | timeline | raw document; `validation_status`; non-DRAFT ⇒ VALID; one CURRENT per title |
| `timeline_events` | timeline | unique `(version, event_key)`; range index `(version, start_ms)` |
| `variation_options` | timeline (write) / variations (read) | per-type field CHECKs; one default per point; `UNIQUE(timeline_event_id, id)` FK target |
| `subtitle_tracks`, `subtitle_cues` | variations | one ACTIVE per title+locale; stable `cue_key` |
| `watch_spaces` | watchspace | **`host_user_id` is the only host source of truth**; composite FK pinned version → same title; lifecycle CHECKs; `last_sequence_no`, `state_version`; `trivia_enabled`; `session_correlation_id` |
| `room_subtitle_tracks` | variations | composite FKs: same title, track locale = row locale |
| `watch_space_participants` | watchspace | membership intervals; one active per room+user; `leave_reason`; no role column |
| `watch_space_moderation` | watchspace | mute/removal survive rejoin |
| `room_events` | watchspace | unique `(room, sequence_no)`; idempotency; closed `event_type` catalog |
| `ai_questions` | ai | composite FKs: room title, room's pinned version; client request dedupe |
| `ai_answer_sources` | ai | composite FKs: cited event ∈ the question's version |
| `ai_pinned_facts` | ai | one active pin per room; answer ∈ room; trivia ∈ pinned version |
| `chat_messages` | chat | unique ledger event; AI messages reference a question |
| `room_votes` | variations | composite FKs: point ∈ pinned version; winner ∈ point; status/field CHECKs; one OPEN per room+point |
| `variation_votes` | variations | PK `(vote, user)`; option ∈ vote's point |
| `room_variation_applications` | variations | one per room+point; VOTE source must equal the recorded winner |
| `title_interactions` | recommendations | derived only; `(user, room)` unique; room title consistency |
| `title_ratings` | recommendations | PK `(user, title)`; 1–5 |
| `watch_space_analytics` | analytics | one snapshot per ended room |

**Role model.** The PRD lists viewer/host/admin roles. Here `ADMIN` is a global `system_role`; `HOST` is derived (`user_id = watch_spaces.host_user_id`); `VIEWER` is any other active member. A user can host one room and view another. [DD, deviation D-07]

The integrity rules that cannot be constraints (R1–R13) are listed at the end of `DATABASE_SCHEMA.sql`.

**Design-review evidence (not application tests):**
- The v0.2 schema was applied to a scratch PostgreSQL 16 instance in the M0 review. Negative tests confirmed the composite FKs and CHECKs reject cross-title pins, cross-version citations/votes, ballots for foreign options, mismatched vote applications, unknown ledger types and invalid lifecycle combinations.
- In M0.1 the v0.3 schema was applied the same way. It rejected a `room_events` row without `occurred_at` and a second `ROOM_ENDED` for one room, and the §9.4 reconciliation query returned the hand-computed values for a fixture ledger.
- The target remains PostgreSQL 17; it is exercised at M1 (M1-V11).

### 5.2 Transaction boundaries (I12)

| Operation | One transaction contains |
|---|---|
| Any lane mutation | domain rows + ledger events + guarded `watch_spaces` update |
| Join | `SELECT … FOR UPDATE` room; moderation/capacity/lock/privacy re-check; membership; `PARTICIPANT_JOINED` |
| Start | status, `started_at`, pinned version, `room_subtitle_tracks`, `session_correlation_id`, `ROOM_STARTED` — `started_at` and the event's `occurred_at` are the same `mutationInstant` |
| End | status, `ended_*`, close memberships (`left_at` = `mutationInstant`), cancel open vote, then ledger `VOTE_CANCELLED` (if any) followed by `ROOM_ENDED` as the room's **final** event — `ended_at` and `ROOM_ENDED.occurred_at` are the same `mutationInstant`. Post-commit and idempotent: interactions, analytics. |
| Host transfer / promotion | `host_user_id`, status, ledger |
| Chat | ledger + `chat_messages` |
| Vote close | vote status + application + `VOTE_CLOSED` + `VARIATION_APPLIED` |
| Timeline publish | previous CURRENT → SUPERSEDED, new → CURRENT |
| Refresh rotation | `SELECT … FOR UPDATE` presented token; revoke/replace; insert new |
| Refresh replay | `SELECT … FOR UPDATE` presented token; revoke every active token of the family (`REUSE_DETECTED`); **commit**; only then return `401 REFRESH_TOKEN_REUSED` (§8.2) |

### 5.3 ER diagram

```mermaid
erDiagram
    APP_USERS ||--o{ REFRESH_TOKENS : owns
    TITLES ||--o{ TITLE_GENRES : has
    GENRES ||--o{ TITLE_GENRES : categorizes
    TITLES ||--o{ TITLE_TIMELINE_VERSIONS : versions
    TITLE_TIMELINE_VERSIONS ||--o{ TIMELINE_EVENTS : contains
    TIMELINE_EVENTS ||--o{ VARIATION_OPTIONS : offers
    TITLES ||--o{ SUBTITLE_TRACKS : has
    SUBTITLE_TRACKS ||--o{ SUBTITLE_CUES : contains
    TITLES ||--o{ WATCH_SPACES : watched_in
    APP_USERS ||--o{ WATCH_SPACES : hosts
    TITLE_TIMELINE_VERSIONS ||--o{ WATCH_SPACES : pinned_by
    WATCH_SPACES ||--o{ ROOM_SUBTITLE_TRACKS : pins
    SUBTITLE_TRACKS ||--o{ ROOM_SUBTITLE_TRACKS : pinned_as
    WATCH_SPACES ||--o{ WATCH_SPACE_PARTICIPANTS : has
    WATCH_SPACES ||--o{ WATCH_SPACE_MODERATION : moderates
    APP_USERS ||--o{ WATCH_SPACE_PARTICIPANTS : joins
    WATCH_SPACES ||--o{ ROOM_EVENTS : records
    ROOM_EVENTS ||--o| CHAT_MESSAGES : projects_to
    WATCH_SPACES ||--o{ AI_QUESTIONS : receives
    AI_QUESTIONS ||--o{ AI_ANSWER_SOURCES : cites
    TIMELINE_EVENTS ||--o{ AI_ANSWER_SOURCES : supports
    WATCH_SPACES ||--o{ AI_PINNED_FACTS : pins
    WATCH_SPACES ||--o{ ROOM_VOTES : opens
    TIMELINE_EVENTS ||--o{ ROOM_VOTES : voted_on
    ROOM_VOTES ||--o{ VARIATION_VOTES : receives
    VARIATION_OPTIONS ||--o{ VARIATION_VOTES : selected
    WATCH_SPACES ||--o{ ROOM_VARIATION_APPLICATIONS : applies
    VARIATION_OPTIONS ||--o{ ROOM_VARIATION_APPLICATIONS : applied_as
    APP_USERS ||--o{ TITLE_INTERACTIONS : generates
    WATCH_SPACES ||--o{ TITLE_INTERACTIONS : derives
    APP_USERS ||--o{ TITLE_RATINGS : rates
    TITLES ||--o{ TITLE_RATINGS : rated
    WATCH_SPACES ||--o| WATCH_SPACE_ANALYTICS : summarizes
```

### 5.4 Timeline document contract (schemaVersion 1)

```json
{
  "schemaVersion": 1,
  "titleId": "<uuid — must equal the URL titleId>",
  "durationMs": 1800000,
  "events": [
    { "eventKey": "intro-detective", "type": "CHARACTER", "startMs": 252000, "endMs": 1500000,
      "payload": { "characterName": "Detective Rios", "description": "A detective introduced at the station." } },
    { "eventKey": "station-trivia-01", "type": "TRIVIA", "startMs": 260000, "endMs": null,
      "payload": { "text": "This station scene uses a fictional location." } },
    { "eventKey": "term-alibi", "type": "GLOSSARY", "startMs": 300000, "endMs": null,
      "payload": { "term": "alibi", "definition": "Evidence that a person was elsewhere." } },
    { "eventKey": "localized-line-01", "type": "VARIATION_POINT", "startMs": 480000, "endMs": null,
      "payload": { "variationType": "SUBTITLE_TEXT", "cueKey": "cue-001" },
      "options": [
        { "key": "original",  "label": "Original line",  "locale": "en",    "textValue": "...", "approved": true, "default": true,  "sortOrder": 0 },
        { "key": "alternate", "label": "Localized line", "locale": "en-IN", "textValue": "...", "approved": true, "default": false, "sortOrder": 1 }
      ] }
  ]
}
```

Required payload fields:
- `SCENE {title, summary}`
- `CHARACTER {characterName, description}`
- `TRIVIA {text}`
- `GLOSSARY {term, definition}`
- `VARIATION_POINT {variationType, cueKey (SUBTITLE_TEXT only)}` + `options[]`

`search_text` is derived server-side from these fields. The authoritative JSON Schema file is checked into the repository at M3 and used by both upload validation and tests. Database IDs are distinct from `eventKey`.

---

## 6. REST API contract

Base path `/api/v1`. JSON. UUID IDs. ISO-8601 UTC timestamps. Authentication required unless marked *public*. Enumerations are `UPPER_SNAKE` in output and accepted case-insensitively on input (so PRD values such as `"normal"` are accepted).

### 6.1 Common rules

- **Identity** always comes from the JWT. A request field named `userId` is never used for authorization. Where the PRD contract includes `userId` (`ai/ask`), a supplied value must equal the token subject, else `403 USER_MISMATCH`.
- **Validation:** Bean Validation on DTOs plus service-layer rules. Unknown JSON properties are rejected (`FAIL_ON_UNKNOWN_PROPERTIES`). The only exceptions are the documented compatibility fields (`userId`, `currentTs`).
- **Pagination:** `page` (≥ 0), `size` (1–50, default 20); responses include `page`, `size`, `totalElements`.
- **Headers:** `X-Correlation-Id` is accepted if it is a valid UUID, otherwise generated; it is always echoed. `Retry-After` is sent on 429/503.
- **Error envelope:** `{ "code", "message", "correlationId", "details": [{ "field", "code", "message" }] }`. No stack traces or internal identifiers.

| HTTP | Codes |
|---|---|
| 400 | `MALFORMED_REQUEST`, `VALIDATION_FAILED`, `POSITION_OUT_OF_RANGE`, `CHAT_MARKUP_NOT_ALLOWED` |
| 401 | `UNAUTHENTICATED`, `TOKEN_EXPIRED`, `INVALID_CREDENTIALS`, `REFRESH_TOKEN_INVALID`, `REFRESH_TOKEN_REUSED` |
| 403 | `FORBIDDEN`, `ADMIN_REQUIRED`, `NOT_ROOM_HOST`, `NOT_ROOM_MEMBER` (PUBLIC_LINK rooms only), `REMOVED_FROM_ROOM`, `CHAT_MUTED`, `USER_MISMATCH` |
| 404 | `NOT_FOUND` (also for INVITE_ONLY rooms the caller is not a member of) |
| 409 | `ROOM_NOT_LIVE`, `ROOM_ENDED`, `ROOM_LOCKED`, `ROOM_FULL`, `INVITE_CODE_INVALID`, `HOST_ROOM_LIMIT`, `NO_CURRENT_TIMELINE`, `INVALID_PLAYBACK_TRANSITION`, `VOTING_DISABLED`, `VOTE_ALREADY_OPEN`, `VOTE_NOT_OPEN`, `VOTE_TOO_LATE`, `VARIATION_POINT_PASSED`, `VARIATION_ALREADY_APPLIED`, `OPTION_NOT_ELIGIBLE`, `NO_TRIVIA_AVAILABLE`, `TIMELINE_NOT_PUBLISHABLE`, `CONFLICT_RETRY`, `EMAIL_TAKEN` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `TIMELINE_INVALID` (with `details[].field` = JSON path) |
| 429 | `RATE_LIMITED` |
| 500 | `INTERNAL_ERROR` (unexpected server error; generic message only; added at M1) |
| 503 | `SERVICE_BUSY`, `ROOM_BUSY` |

Framework-level client errors outside this table (for example 405 or 415) keep their HTTP status and use `MALFORMED_REQUEST`.

`INVITE_CODE_INVALID` is deliberately returned for both unknown and wrong codes to avoid revealing room existence.

**Latency target:** non-AI endpoints P95 < 200 ms in the local test environment [PRD §10.2], measured, never assumed.

### 6.2 Authentication and profile

| Method | Route | Request → Response | Auth / rules |
|---|---|---|---|
| POST | `/auth/register` | `{email, password, displayName}` → `201 {userId}` | *public*; email RFC-valid ≤ 320; password 12–128 chars **and ≤ 72 UTF-8 bytes**; displayName plain text 1–80 |
| POST | `/auth/login` | `{email, password}` → `{accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, user}` | *public*; rate-limited; generic `INVALID_CREDENTIALS` |
| POST | `/auth/refresh` | `{refreshToken}` → new token pair | *public*; rotation + replay rejection (§8.2) |
| POST | `/auth/logout` | `{refreshToken}` → `204` | revokes the token's family |
| GET | `/me` | → profile | |
| PATCH | `/me` | `{displayName?, subtitleLocale?}` → profile | locale = valid BCP 47; triggers `room.subtitle.variants` for the user's live sockets |
| GET | `/me/dashboard` | → `{recentTitles[], activeRooms[{…, canQuickRejoin}], upcomingRooms[], summary{roomsHosted, roomsJoined, watchedSeconds, aiQuestionsAsked}}` | §9.3 |
| GET | `/me/watch-spaces?status=ENDED\|LIVE\|SCHEDULED&page&size` | → paged rooms, each with role, title, timestamps and (for ENDED) analytics summary | past-room listing (I4) |
| GET | `/me/watch-history?page&size` | → paged derived interactions | |
| GET | `/me/recommendations?limit=1..20` | → `{items[{titleId, score, reasons[]}], strategy}` | |

### 6.3 Catalogue, timeline, subtitles, ratings

| Method | Route | Notes |
|---|---|---|
| GET | `/titles?query=&genre=&page&size` | Published titles only. |
| GET | `/titles/{titleId}` | Details + genres. |
| GET | `/titles/{titleId}/timeline?from={sec}&to={sec}[&watchSpaceId=]` | **[PRD §7.3.2]** `from`/`to` are non-negative integer **seconds**, `from ≤ to`, span ≤ 3 600 s; converted to ms at the boundary. Uses the CURRENT version, or the room's pinned version when `watchSpaceId` is given (members only). Response: `{titleId, timelineVersionId, events[{eventId, eventKey, type, ts, startMs, endMs, …payload fields, options[{id, label}] for approved options of VARIATION_POINTs}]}`. `ts` = `startMs / 1000` (seconds, up to 3 decimals). |
| GET | `/titles/{titleId}/subtitles?locale=` | ACTIVE track metadata (`trackId`, `locale`, `versionNumber`, `assetUri`, `checksum`). |
| PUT | `/titles/{titleId}/rating` | `{rating: 1..5}` → `200`; upsert into `title_ratings` (C6); invalidates the recommendation cache. |
| DELETE | `/titles/{titleId}/rating` | Removes the caller's rating. |

### 6.4 Watch Spaces

| Method | Route | Request → Response | Auth |
|---|---|---|---|
| POST | `/watch-spaces` | **[PRD §7.3.1]** `{titleId, name?, privacy?, maxParticipants? (2–50, def 25), aiVerbosity? (def NORMAL), votingEnabled? (def true), triviaEnabled? (def true), scheduledAt? (future)}` → `201 {watchSpaceId, inviteCode, status, hostUserId, createdAt, settings}` | any user; title PUBLISHED; `name` defaults to "{title} watch party" |
| GET | `/watch-spaces/{id}` | → room details | member; PUBLIC_LINK preview for non-members; INVITE_ONLY non-member → 404 |
| POST | `/watch-spaces/join` | `{inviteCode}` → membership + `watchSpaceId` | join by code only; rate-limited |
| POST | `/watch-spaces/{id}/join` | `{inviteCode?}` → membership | §4.2 rules; rate-limited |
| POST | `/watch-spaces/{id}/leave` | → `204` | member |
| POST | `/watch-spaces/{id}/start` | → room | host |
| POST | `/watch-spaces/{id}/end` | → room | host |
| GET | `/watch-spaces/{id}/participants` | → `[{userId, displayName, roomRole, connected, connectionQuality, muted}]` | member |
| PATCH | `/watch-spaces/{id}/settings` | §4.11 | host |
| POST | `/watch-spaces/{id}/lock` | `{locked}` | host |
| POST | `/watch-spaces/{id}/invite-code` | → `{inviteCode}` | host; rate-limited |
| POST | `/watch-spaces/{id}/participants/{userId}/mute` | `{muted}` | host |
| DELETE | `/watch-spaces/{id}/participants/{userId}` | → `204` | host |
| POST | `/watch-spaces/{id}/host-transfer` | `{newHostUserId}` | host |
| GET | `/watch-spaces/{id}/chat?beforeSequence=&afterSequence=&limit=` | → ordered messages | member |
| GET | `/watch-spaces/{id}/subtitles?locale=` | → pinned track + alternates | member |
| GET | `/watch-spaces/{id}/analytics` | → snapshot (final after end; live counts reconstructed on request before end) | host, any past/present member, or ADMIN |

Path `{userId}` parameters identify the **target** of a moderation action; the actor is always the JWT subject.

### 6.5 AI, trivia, variations

| Method | Route | Request → Response | Auth |
|---|---|---|---|
| POST | `/watch-spaces/{id}/ai/ask` | **[PRD §7.3.3]** `{question (1–500, plain text), clientRequestId?, userId? (must equal token), currentTs? (seconds, advisory only)}` → `200` §4.8 response | active member; rate-limited |
| POST | `/watch-spaces/{id}/ai/pins` | `{aiQuestionId}` or `{timelineEventId}` → pin | host |
| DELETE | `/watch-spaces/{id}/ai/pins/current` | → `204` | host |
| POST | `/watch-spaces/{id}/trivia/next` | → surfaced trivia | host |
| POST | `/watch-spaces/{id}/votes` | `{timelineEventId, durationSeconds?}` → vote | host |
| GET | `/watch-spaces/{id}/votes/{voteId}` | → `{status, options, closesAt, myOptionId, results? (only when not OPEN)}` | member |
| PUT | `/watch-spaces/{id}/votes/{voteId}/ballot` | `{optionId}` → `200 {myOptionId}` | active member |
| POST | `/watch-spaces/{id}/votes/{voteId}/cancel` | → vote | host |
| POST | `/watch-spaces/{id}/variations/{timelineEventId}/selection` | `{optionId}` → application | host |
| GET | `/watch-spaces/{id}/variations` | → `{applied[], suggestions[] (host only)}` | member |

`ai/ask` always returns `200` once the question is recorded, with `status` in the body (`ANSWERED`, `INSUFFICIENT_CONTEXT`, `FAILED`). Authorization and validation failures use the error envelope.

### 6.6 Admin metadata management (global ADMIN; re-checked against the DB role on every admin call)

| Method | Route | Notes |
|---|---|---|
| POST | `/admin/titles` | `{slug, name, description?, durationMs, videoAssetUri, defaultLocale, genres[]}` |
| PATCH | `/admin/titles/{titleId}` | Includes `status` (DRAFT/PUBLISHED/ARCHIVED) and genres. |
| POST | `/admin/titles/{titleId}/timeline-versions` | Upload (§4.7) → `201` VALID or `422 TIMELINE_INVALID` (version stored as INVALID). |
| POST | `/admin/timeline-versions/{versionId}/validate` | Re-validate a DRAFT. |
| POST | `/admin/timeline-versions/{versionId}/publish` | VALID only; atomic supersede. |
| GET | `/admin/timeline-versions/{versionId}` | Status, errors, counts. |
| GET | `/admin/titles/{titleId}/timeline-versions` | Version history. |

### 6.7 Health

`/actuator/health/liveness`, `/actuator/health/readiness` (groups include `db` and `realtime`), `/actuator/info`, `/actuator/metrics` (ADMIN only). Health details are shown only to ADMIN.

---

## 7. WebSocket contract

### 7.1 Connection and authentication

- `GET /ws/watch-spaces/{watchSpaceId}` with `Authorization: Bearer <accessToken>` on the handshake. Tokens in query strings are rejected.
- The handshake requires a valid token **and** an active membership (REST join first); otherwise the upgrade is refused with 401/403/404.
- One socket per user per room: a new connection replaces the old one, which is closed with 4409.
- After upgrade the server sends `room.snapshot` and then `room.chat.history`.
- **Token renewal (I16):**
  - The server sends `room.auth.expiring {expiresAtMs}` 60 s before the access token expires.
  - The client obtains a new access token via REST `/auth/refresh` and sends `room.auth.refresh {accessToken}`.
  - The server verifies the signature, expiry and same subject, then replies `room.auth.refreshed {expiresAtMs}`.
  - Without renewal, the socket is closed with 4401 at expiry.
- **Re-validation:** every sensitive command re-checks token expiry, active membership, removal, mute (chat) and host status (playback/moderation) against current state. Handshake authentication alone is never sufficient. [PRD §10.1]

### 7.2 Envelopes

Client → server:

```json
{ "event": "room.chat.send", "watchSpaceId": "<uuid>", "requestId": "<uuid>", "payload": { "body": "Hello everyone" } }
```

Server → client:

```json
{ "event": "room.chat.message", "watchSpaceId": "<uuid>", "eventId": "<uuid>", "sequenceNo": 42,
  "ts": 1790000000000, "correlationId": "<uuid>", "requestId": "<uuid of the causing command, if any>",
  "payload": { "messageId": "<uuid>", "userId": "<uuid>", "body": "Hello everyone", "positionMs": 123400, "tsSeconds": 123.4 } }
```

- `ts` = server epoch milliseconds. [PRD §7.3.4]
- `sequenceNo` is present only on durable (ledger-backed) events. Ephemeral events omit it.
- Positions are integer milliseconds. Where the PRD shows seconds, a read-only `…Seconds` companion field is included (`positionSeconds`, `tsSeconds`). Seconds are never accepted as WebSocket input.

### 7.3 Client → server events

| Event | Payload | Rules |
|---|---|---|
| `room.playback.command` | `{command: PLAY\|PAUSE\|SEEK, positionMs?}` | Current host; §4.1; lane. |
| `room.playback.buffering` | `{buffering}` | Current host; §4.1; lane. |
| `room.playback.report` | `{positionMs, playbackRate, clientSentAtMs}` | Member; ≤ 4/s; telemetry only. |
| `room.sync.ping` | `{clientSentAtMs}` | Member; ≤ 4/s. |
| `room.chat.send` | `{body}` | Active, non-muted member, room not chat-muted; §8.3; lane. |
| `room.typing.start` / `room.typing.stop` | `{}` | Member; ≤ 2/s; ephemeral. |
| `room.reaction.send` | `{emoji}` | Member; allow-list; ≤ 5/s; ephemeral. |
| `room.ai.ask` | `{question, clientRequestId?}` | Active member; rate-limited; §4.8. |
| `room.variation.vote` | `{voteId, optionId}` | Active member; §4.10. |
| `room.subtitle.locale` | `{locale}` | Member; session override; private reply `room.subtitle.variants`. |
| `room.auth.refresh` | `{accessToken}` | §7.1. |

Unknown event names, malformed JSON, frames > 16 KiB or invalid payloads → `room.error`. Five such errors within 10 s → close 4400.

### 7.4 Server → client events

| Event | Durable | Payload (key fields) |
|---|---|---|
| `room.snapshot` | — | `room{status, settings, hostUserId, sessionCorrelationId}`, `playback{state, positionMs, positionSeconds, serverTimeMs, durationMs, stateVersion}`, `lastSequenceNo`, `participants[{userId, displayName, roomRole, connected, connectionQuality, muted}]`, `you{roomRole, muted}`, `activeVote?{…, myOptionId}`, `appliedVariations[]`, `pinnedFact?`, `subtitles{locale, trackId, assetUri, alternates[]}`, `auth{expiresAtMs}` |
| `room.chat.history` | — | Last 50 messages in sequence order. |
| `room.playback.update` | yes | `{state, action: PLAY\|PAUSE\|SEEK\|BUFFERING_START\|BUFFERING_END\|AUTO_PAUSE, reason?, positionMs, positionSeconds, serverTimeMs, stateVersion, issuedBy}` |
| `room.presence.update` | JOINED/LEFT/REMOVED: yes; DISCONNECTED/RECONNECTED/QUALITY: no | `{participantId, action, participantCount, roomRole, connectionQuality?}` |
| `room.chat.message` | yes | `{messageId, userId, messageType, body, positionMs, tsSeconds}` |
| `room.chat.typing` / `room.chat.reaction` | no | `{userId, isTyping}` / `{userId, emoji}` |
| `room.sync.pong` | no | `{clientSentAtMs, serverReceivedAtMs, serverSentAtMs}` |
| `room.sync.correction` | no (private) | `{expectedPositionMs, driftMs, correctionMode, stateVersion}` |
| `room.host.disconnected` | yes | `{hostUserId, graceEndsAtMs, positionMs}` |
| `room.host.reconnected` | yes | `{hostUserId}` |
| `room.host.transferred` | yes | `{previousHostId, newHostId, reason: TRANSFER\|PROMOTED}` |
| `room.settings.update` | yes | full settings object |
| `room.lifecycle.update` | yes | `{status, endedReason?}` |
| `room.ai.trivia` | yes | `{eventId, text, positionMs, tsSeconds, sourceTimelineEventId, trigger: AUTO\|ON_DEMAND}` |
| `room.ai.answer` | yes | §4.8 response + `userId` |
| `room.ai.pinned` / `room.ai.unpinned` | yes | `{pinId, pinType, aiQuestionId?, timelineEventId?, text}` |
| `room.variation.voteOpen` | yes | `{voteId, variationId, options[{id, label}], closesAt, effectivePositionMs}` — no counts |
| `room.variation.voteClosed` | yes | `{voteId, results[{optionId, count}], winningOptionId}` |
| `room.variation.voteCancelled` | yes | `{voteId, reason}` |
| `room.variation.applied` | yes | `{variationId, winningOptionId, source, effectivePositionMs, stateVersion}` — identical for all |
| `room.variation.suggested` | no (host only) | `{suggestions[{variationId, optionIds, reason}]}` |
| `room.subtitle.variants` | no (private) | `{locale, trackId, alternates[{variationId, cueKey, optionId, textValue, effectivePositionMs}]}` |
| `room.auth.expiring` / `room.auth.refreshed` | no (private) | `{expiresAtMs}` |
| `room.ack` | no (private) | `{requestId, sequenceNo?}` for accepted commands without their own broadcast (no-ops, ballots) |
| `room.error` | no (private) | `{code, message, requestId?}` — same codes as REST; never stack traces or secrets |

### 7.5 Close codes (I16)

| Code | Meaning |
|---|---|
| 1000 | Normal closure (client leave) |
| 1001 | Server shutting down |
| 1009 | Inbound frame too large |
| 1011 | Unexpected server error |
| 4400 | Repeated protocol/validation errors |
| 4401 | Authentication invalid or expired without renewal |
| 4403 | No longer an active member |
| 4404 | Room not found |
| 4408 | Slow consumer (outbound buffer overflow) |
| 4409 | Replaced by a newer connection of the same user |
| 4410 | Room ended |
| 4411 | Removed from room |
| 4429 | Rate-limit abuse |

### 7.6 Slow-consumer and fan-out policy (I16)

- Each session has a bounded outbound queue (default 256 messages; single sender subscription).
- Above a high-water mark (75 %), **ephemeral** events (typing, reactions, presence quality, corrections) are dropped for that session.
- Durable events are never dropped silently. Overflow closes the socket with 4408, and the client recovers through reconnect, snapshot and `afterSequence` history.
- Fan-out iterates the room's sessions without blocking. Target: last delivery < 500 ms after server receipt for rooms up to 50 participants [PRD §10.2], measured.

---

## 8. Security and privacy

### 8.1 Authentication tokens

- **Access tokens:** JWT, **HS256**, signed and validated with Nimbus via Spring Security 7.1's resource-server support (`spring-boot-starter-security-oauth2-resource-server`; API details confirmed at M1-V3).
  - Key: `JWT_SIGNING_KEY` environment variable, base64, ≥ 256 bits of random data; startup fails if missing or shorter.
  - Claims: `iss` (configured), `aud = ai-watch-spaces`, `sub` (userId), `sys_role`, `iat`, `exp`, `jti`.
  - TTL default 15 min; configuration above 60 min is rejected at startup [PRD ≤ 1 h]. Clock skew tolerance 30 s.
- **Admin endpoints** re-read `system_role` from the database, so a demotion takes effect immediately.

### 8.2 Refresh tokens (rotation and replay rejection)

- 256-bit random opaque value; only its SHA-256 hash is stored. TTL 14 days. Each login starts a new `family_id`.
- **Rotation:** lock the presented row (`SELECT … FOR UPDATE`). If it is active and unexpired → revoke it (`ROTATED`), link `replaced_by_token_id`, and issue a new token in the same family.
- **Replay:** the presented token is already revoked (rotated or logged out) → revoke every still-active token in its family (`REUSE_DETECTED`), including any token legitimately issued by an earlier rotation.
- Unknown or expired token → `401 REFRESH_TOKEN_INVALID` (read-only; nothing to commit).
- Logout revokes the family (`LOGOUT`).
- Purging old rows is deferred (O4).

**Replay transaction outcome (must hold):** the family revocation is a security side effect that has to survive the rejected request.

1. `RefreshTokenService.refresh(...)` is a blocking `@Transactional` method that **never throws to signal replay**. It returns a sealed `RefreshOutcome`: `Rotated(tokenPair)`, `ReuseDetected(familyId)` or `Invalid`. This follows the commit-then-reject rule in ADR-003 §4.
2. For `ReuseDetected`, the method performs the family revocation `UPDATE` and returns normally, so the transaction **commits**.
3. The reactive web adapter receives the outcome only after the commit has completed on the `db-*` thread. It then maps `ReuseDetected` → `401 REFRESH_TOKEN_REUSED` and `Invalid` → `401 REFRESH_TOKEN_INVALID`.
4. Exception-driven rollback is reserved for genuine failures (database errors). If the revocation itself fails, the response is `503 SERVICE_BUSY`, not `401`, so a 401 always implies the revocation is durable.
5. `noRollbackFor` and `REQUIRES_NEW` are not used for this flow.

**Concurrent use of one token:** the row lock serializes two simultaneous refreshes with the same token. The first rotates and commits; the second then sees a revoked token and is treated as a replay, revoking the family (including the token just issued to the first). This strict behaviour is intended for the MVP; there is no reuse grace window.

**Required tests (M2):**
- After a replayed refresh returns `401 REFRESH_TOKEN_REUSED`, a **separate** database read confirms every token of the family has `revoked_reason = 'REUSE_DETECTED'` (or an earlier terminal reason).
- A subsequent refresh with the newest token of that family fails.
- With a fault injected into the revocation `UPDATE`, the response is not `401` and no token state changes.

### 8.3 Input validation and plain-text policy

All user-supplied text — chat bodies, AI questions, display names, room names and timeline payload strings — is **plain text**:

1. Normalize to Unicode NFC and trim.
2. Reject C0/C1 control characters except `\n` and `\t`; at most 20 line breaks.
3. Reject markup: any `<` immediately followed by a letter, `/`, `!` or `?` → `400 CHAT_MARKUP_NOT_ALLOWED` (`VALIDATION_FAILED` with a field code outside chat). A bare `<` as in "a < b" is allowed.
4. Lengths:

| Field | Length |
|---|---|
| Chat | 1–1 000 |
| AI question | 1–500 |
| Display name | 1–80 |
| Room name | 1–120 |
| Timeline strings | per JSON Schema |

5. Store the normalized text verbatim. It is never interpreted as HTML on the server, and JSON serialization escapes it.
6. The API reference states that any client rendering these fields as HTML must output-encode them. The server-side rejection prevents stored-XSS payloads from entering chat history [PRD §10.1].

Emoji reactions: fixed allow-list. Locales: BCP 47 validation. IDs: UUID format.

### 8.4 Safe document parsing

- **YAML** is parsed with Jackson 3's `YAMLMapper` (`tools.jackson.dataformat.yaml`) into a `JsonNode` tree only. Never bind to arbitrary types; never enable default typing or polymorphic deserialization. Wiring confirmed at M1-V7: Jackson 3.1 YAML is built on snakeyaml-engine, so the limits are set with `LoadSettings` (`allowDuplicateKeys=false`, `allowRecursiveKeys=false`, `codePointLimit=2 MiB`) plus Jackson `StreamReadConstraints.maxNestingDepth=32` and `STRICT_DUPLICATE_DETECTION`. **YAML aliases are rejected** (including `<<:` merge keys): Jackson does not expand aliases, so `maxAliasesForCollections` has no effect and an aliased value would parse as the anchor's name. The YAML backend does not apply `maxStringLength`; YAML size is bounded by the code-point limit and field lengths by the JSON Schema.
- **JSON** uses Jackson 3 `StreamReadConstraints`: max nesting 32, max string length 20 000.
- Timeline schema validation uses `json-schema-validator` 3.0.x over the same Jackson 3 tree.
- Uploads are capped at 2 MiB (`413` above).
- Uploaded content is never executed or used as a template.

### 8.5 Passwords

`DelegatingPasswordEncoder` with **BCrypt, strength 12** (`{bcrypt}` prefix, so the algorithm can migrate later).
- Policy: 12–128 characters and ≤ 72 UTF-8 bytes (BCrypt's input limit), no composition rules.
- Login uses a constant generic error and rate limiting.

### 8.6 Authorization

| Role | Determined by |
|---|---|
| ADMIN | Global `system_role` |
| HOST | `user_id = watch_spaces.host_user_id` (single source of truth) |
| VIEWER | Any other active member |

- Every REST service method receives the explicit principal and checks the role.
- WebSocket commands are re-checked on every sensitive action (§7.1).
- Negative tests cover viewer-on-host, viewer-on-admin, non-member, removed-user and muted-user actions.

### 8.7 Rate limits (in-memory token buckets in `common`; single-instance assumption documented)

| Action | Default |
|---|---|
| Login | 5/min per normalized email; 20/min per IP |
| Register | 5/hour per IP |
| Join (both join routes) | 10/min per user; 30/min per IP |
| Invite-code rotation | 5/hour per room |
| Chat send | 5/s per user per room (burst 10) |
| AI ask | 3/min per user per room; 20/min per room |
| Ballot | 10/min per user per vote |
| Typing / reactions | 2/s / 5/s per user |
| Sync ping / report | 4/s each per session |

Exceeded → `429 RATE_LIMITED` (REST) or `room.error` (WebSocket). Sustained abuse closes the socket with 4429.

### 8.8 Secrets and logging

- `JWT_SIGNING_KEY`, `INVITE_CODE_PEPPER`, DB credentials and `LLM_API_KEY` come only from environment variables (or an ignored local `.env`). `.env.example` contains placeholders only. [PRD §10.1, §14.2]
- Logs never contain passwords, tokens, invite codes, refresh hashes or API keys.
- AI logs record question/answer **metadata** (IDs, status, latency, source IDs, model). Raw question text is logged only at DEBUG in the local profile.

---

## 9. Recommendations, ratings, dashboard and analytics

### 9.1 Inputs

- `title_interactions`: derived watch coverage and completion (§4.12).
- `title_ratings`: explicit 1–5 ratings, independent of rooms (C6).
- `title_genres`: genre affinity.
- **Co-watch** = users who share a `watch_space_id` in `title_interactions`. [PRD §6.3 lists ratings, completion, genre affinity, co-watch]

### 9.2 Hybrid scorer [DD]

- **Content score:** cosine similarity between the user's genre-affinity vector (completed or rated ≥ 4, weighted by rating) and each candidate's genres.
- **Collaborative score:** item–item co-occurrence over users who completed or rated ≥ 4, plus a small co-watch bonus.
- **Blend:** 0.6 content / 0.4 collaborative when both exist; otherwise the available signal. Cold start ranks by completion-rate popularity.
- **Exclusions:** titles the user completed, and titles of the user's currently active rooms.
- Each item returns explainable `reasons[]`.
- **Freshness:** results are computed on request with a small per-user cache, invalidated on new ratings and on interaction finalization. No retrain or redeploy is needed. [PRD §6.3 AC]
- **Offline evaluation:** a deterministic synthetic interaction generator (fixed seed) and a fixed held-out split; report Precision@5 against the ≥ 0.6 target honestly, with the data-generation method documented. [PRD §1.3]

### 9.3 Dashboard and past rooms (I4)

- `/me/dashboard`:
  - Recent titles from interactions.
  - Active rooms (memberships in non-ended rooms, with `canQuickRejoin` = previously a member and not removed).
  - Upcoming `SCHEDULED` rooms.
  - Summary totals.
- `/me/watch-spaces` lists rooms the user hosted or joined, paginated, with the analytics summary for ended rooms.

### 9.4 Analytics (ledger-derived; snapshot written at end)

The snapshot is computed **only from `room_events`**, processed in `sequence_no` order, after `ROOM_ENDED` has committed. Because `ROOM_ENDED` is the room's final event (ADR-005), the input set is closed and the computation is repeatable.

**Canonical timestamps.**
- Session start = `occurred_at` of the room's `ROOM_STARTED` event.
- Session end = `occurred_at` of the room's `ROOM_ENDED` event.
- These are the same instants stored in `watch_spaces.started_at` / `ended_at`, because each pair is written from one `mutationInstant` (ADR-005).
- The schema allows at most one `ROOM_CREATED`, `ROOM_STARTED` and `ROOM_ENDED` per room (partial unique index).

| Metric | Exact definition |
|---|---|
| `duration_seconds` | `floor((ROOM_ENDED.occurred_at − ROOM_STARTED.occurred_at)` in seconds`)`; **0** if the room has no `ROOM_STARTED` (e.g. `UNSTARTED_EXPIRED`). Includes time spent `HOST_DISCONNECTED` (wall-clock session length). Java: `Duration.between(start, end).getSeconds()` (floors for non-negative durations). |
| `peak_concurrent_participants` | Over **all** of the room's events (including the pre-start lobby), ordered by `sequence_no`: a running sum of `+1` for `PARTICIPANT_JOINED`, `−1` for `PARTICIPANT_LEFT` or `PARTICIPANT_REMOVED`, `0` otherwise, starting at 0. The metric is the maximum of that sum. `ROOM_ENDED` closes remaining memberships without per-user events and cannot raise the peak. Counts memberships, not sockets. |
| `ai_question_count` | Number of `AI_QUESTION_CREATED` events. |
| `trivia_card_count` | Number of `TRIVIA_SURFACED` events (auto + on-demand). |
| `chat_message_count` | Number of `CHAT_MESSAGE_POSTED` events (user chat only; AI and system messages have other event types). |
| `source_last_sequence` | `sequence_no` of `ROOM_ENDED` (= `watch_spaces.last_sequence_no` at end). |

**Reconciliation query (the test's independent oracle; `:room` = watch space id):**

```sql
WITH ev AS (
    SELECT sequence_no, event_type, occurred_at
    FROM room_events
    WHERE watch_space_id = :room
),
running AS (
    SELECT SUM(CASE event_type
                   WHEN 'PARTICIPANT_JOINED'  THEN 1
                   WHEN 'PARTICIPANT_LEFT'    THEN -1
                   WHEN 'PARTICIPANT_REMOVED' THEN -1
                   ELSE 0 END)
           OVER (ORDER BY sequence_no ROWS UNBOUNDED PRECEDING) AS active
    FROM ev
)
SELECT
    COALESCE(FLOOR(EXTRACT(EPOCH FROM (
        (SELECT occurred_at FROM ev WHERE event_type = 'ROOM_ENDED')
      - (SELECT occurred_at FROM ev WHERE event_type = 'ROOM_STARTED'))))::int, 0)  AS duration_seconds,
    COALESCE((SELECT MAX(active) FROM running), 0)                                  AS peak_concurrent_participants,
    (SELECT COUNT(*) FROM ev WHERE event_type = 'AI_QUESTION_CREATED')::int          AS ai_question_count,
    (SELECT COUNT(*) FROM ev WHERE event_type = 'TRIVIA_SURFACED')::int              AS trivia_card_count,
    (SELECT COUNT(*) FROM ev WHERE event_type = 'CHAT_MESSAGE_POSTED')::int          AS chat_message_count,
    (SELECT sequence_no FROM ev WHERE event_type = 'ROOM_ENDED')                     AS source_last_sequence;
```

**Reconciliation test [PRD §6.3 AC] — exact equality, no tolerances:**

1. Run a scripted fixture session through the real lane with a **controllable `Clock`** stepped by known amounts, so expected values are known in advance. Cover: lobby joins before start, a leave, a removal, a rejoin, a host disconnect and reconnect, chat, AI questions (including a deduplicated retry), auto and on-demand trivia, and end.
2. Assert the stored `watch_space_analytics` row equals the oracle query above, column by column.
3. Assert the oracle equals the hand-computed expected values for the fixture.
4. Assert `watch_spaces.started_at = ROOM_STARTED.occurred_at`, `watch_spaces.ended_at = ROOM_ENDED.occurred_at`, and `source_last_sequence = watch_spaces.last_sequence_no`.
5. Assert `ai_question_count` = number of `ai_questions` rows for the room (a deduplicated retry creates neither a row nor an event).
6. Repeat once with the system clock (values unknown in advance) and assert steps 2, 4 and 5.

This test also provides the "fully manually verified test session" evidence: the fixture's expected values are written out by hand in the test.

---

## 10. Non-functional requirements and test evidence

| PRD requirement | Measurement |
|---|---|
| Non-AI REST P95 < 200 ms | JUnit-driven load profile; report endpoint, sample count, P50/P95/P99, hardware. |
| AI answer P95 < 3 s | End-to-end and provider latency from `ai_questions`; failures and timeouts reported too. |
| Drift < 100 ms target, < 250 ms ceiling | Scripted clients' reported drift before/after correction; network conditions stated; simulation caveat (§4.4). |
| Joiner synced within 250 ms in 2 s | Harness: time from socket open to first report within 250 ms of expected. |
| Host pause/seek reflected within 1 s | Server receive → last client receive timestamps. |
| Fan-out < 500 ms | Same, at 3, 10 and as many clients as hardware allows (target 50); report the tested count honestly. |
| Trivia < 1 s for ≥ 95 % of markers | Crossing time vs received time, including seek and reconnect scenarios. |
| Join success > 99 % | Harness counts attempts vs synchronized joins. |
| Precision@5 ≥ 0.6 | Fixed held-out set. |
| Trivia/context QA ≥ 90 % | Manual review of a fixed sample; record pass rate. |
| RBAC server-side | Negative tests, including rejected viewer token on host/admin actions. |
| Analytics reconcile exactly | §9.4 test. |

### Test strategy

**Unit tests:**
- Playback transition table (every row of §4.1).
- Clock and drift maths; correction modes; connection-quality classification.
- Vote tally, ties and no-vote default.
- Retrieval set selection and spoiler exclusion.
- AI output validation; plain-text policy; invite-code normalization.
- Interaction-coverage maths; recommendation scorer.

**Integration tests (Testcontainers PostgreSQL):**
- Flyway migrations equal the target schema.
- Constraint negatives (composite FKs).
- Refresh rotation and replay, including the committed-revocation tests of §8.2; RBAC.
- Analytics reconciliation with a controllable clock (§9.4) and canonical-instant equality (ADR-005).
- JSONB round-trip through Hibernate 7 with Jackson 3 (M1-V5).
- **Capacity race:** 20 concurrent joins into a capacity-5 room → exactly 4 succeed (the host holds one seat).
- **Lane failure injection:** forced DB error → no broadcast and unchanged `RoomState`.
- **Optimistic-guard conflict:** out-of-lane write → `CONFLICT_RETRY` and re-hydration.
- **Blocking boundary:** connection acquired only on `db-*` threads.
- Timeline publish/supersede; recovery re-hydration with a controllable clock.

**WebSocket tests (JUnit + JDK `java.net.http.WebSocket` scripted clients):**
- Host A + participants B/C: play/pause/seek.
- **Event ordering:** concurrent chat from 3 clients interleaved with host commands → every client receives a gap-free, identical `sequenceNo` order that matches the DB.
- Reconnect without duplicate join; host disconnect → promotion.
- Removed/muted enforcement; token renewal and 4401; slow consumer 4408.
- Trivia toggle; locale alternates do not change `stateVersion`.
- Vote close, apply and cancel-on-seek.

**Demonstration:** `@Tag("demo")` JUnit scenarios run with `mvn -Pdemo verify` print a readable transcript of the end-to-end flows (Track B replacement for screen recordings). [PRD §4.3]

---

## 11. Operations and observability

- **Docker Compose:** application + PostgreSQL 17; local asset directory volume. The LLM provider is external and configurable (OD-2).
- **Configuration:** `application.yml` holds defaults only; secrets and environment-specific values come from environment variables. All tunables in this document (grace periods, timeouts, thresholds, limits) are configuration properties with the stated defaults.
- **Correlation (I5):**
  - `correlationId` per HTTP request / WebSocket command (stored in `room_events.correlation_id`).
  - `sessionCorrelationId` per watch session (assigned at start, stored on the room, included in the snapshot) — tags every room and AI log line. [PRD §10.3]
- **Structured logs:** Spring Boot's built-in structured console logging (JSON). Every WebSocket room event and every AI request/response is logged with `watchSpaceId`, `sessionCorrelationId`, `eventId`, `sequenceNo`, `correlationId`, actor ID and latency, excluding sensitive fields.
- **Health (I5):** liveness; readiness = `db` + custom **`realtime`** indicator (WebSocket handler mapping registered, room registry responsive, DB executor and lane queues below saturation, not shutting down, recovery finished). [PRD §10.3: API and real-time gateway]
- **Metrics (Micrometer):** REST latency by route; connected sessions and rooms; lane queue depth and mutation latency; fan-out duration; drift distribution; AI end-to-end and provider latency and outcome counts; trivia lateness; join failures by code; reconnects; slow-consumer closes; timeline validation failures.

---

## 12. Implementation milestones

Each milestone ends with passing tests and a written report of what passed, failed or could not be run. Scope is cut before tests or documentation. [PRD §14.2]

| # | Milestone | Exit criteria |
|---|---|---|
| **M0 / M0.1 / M0.2** | Design revision, review revision and correction (this document, schema, CLAUDE.md) | **Approved 2026-10-26.** M1 authorized. |
| M1 | Maven scaffold (Spring Boot 4.1.2, Java 21), packages per module, Docker Compose, configuration, DB executor, error envelope, correlation filter, structured logging, health (incl. `realtime` stub), and the **M1 verification checklist M1-V1…V13** (ADR-002) | Context loads on Testcontainers PostgreSQL 17; enforcer (incl. Jackson 2 ban) passes; resolved versions recorded in `docs/dependency-versions.md`; each M1-V item reported as passed, failed or resolved by its documented fallback; health test; blocking-boundary test; JSONB round-trip with Jackson 3. |
| M2 | Flyway V1; register/login/refresh/logout; `/me`; reactive security chain; rate limiting for auth | 401/403/400 tests; TTL ≤ 1 h enforced; rotation; replay revokes the family **and the revocation is committed before the 401** (the three §8.2 tests); no secrets in logs. |
| M3 | Flyway V2; timeline JSON Schema v1; seed title + timeline + subtitle tracks; title and timeline read endpoints (seconds ↔ ms) | Range/overlap tests; unit-conversion tests; seed validates as VALID; **subtitle immutability (R13):** any attempt through the application to modify or delete the cues or asset of an `APPROVED`/`ACTIVE` track is rejected, a change is only possible as a new `version_number`, and seeded asset files match their recorded SHA-256 checksums. |
| M4 | Flyway V3; room lane + ledger; create/get/join (both)/leave/start/end; invite codes; lock; settings | Capacity race; lock/privacy/removal negatives; sequence monotonicity under concurrency; guard-conflict test. |
| M5 | WebSocket handshake, snapshot, presence, reconnect grace, token renewal, close codes | Non-member and invalid-token rejection; no duplicate join on reconnect; 4401/4409/4411 tests. |
| M6 | Playback mutations (§4.1) with persist → apply → broadcast | Viewer command rejected; host commands reach 3 scripted clients in order; `stateVersion` ordering; fan-out latency recorded. |
| M7 | Sync ping/pong, drift reports, corrections, connection quality | Mid-playback joiner within 250 ms in 2 s (measured); thresholds unit-tested; first drift numbers recorded honestly. |

After M7 the Week 1 slice is complete. Then, in order:

- **Week 2:** chat/history/typing/reactions (V4), moderation and host transfer, host disconnect/promotion and recovery, timeline upload/validate/publish, trivia (auto + on-demand), AI Q&A and pins (V5).
- **Week 3:** subtitles and locale alternates (including the §4.9 tests that a pinned track's cues and asset stay unchanged and servable until the room ends, even after the track is archived), votes and host selection (V6), interactions, ratings, recommendations, dashboard, analytics (V7).
- **Week 4:** hardening, load harness, performance report, security review, Postman collection, documentation, retrospective, demo transcript.

AI, variations and recommendations do not start until M7 passes.

---

## 13. Decisions to freeze on approval

Frozen by the user's approval of v0.4 on 2026-10-26.

1. Backend-only (Track B); no UI.
2. One Spring Boot modular monolith; no microservices.
3. Java 21, Spring Boot 4.1.2, Maven; WebFlux REST/WebSockets (plain JSON, no STOMP); Spring Security 7 with JWT; Spring Data JPA with Hibernate 7; Jackson 3 only (no Jackson 2); json-schema-validator 3.0.x; PostgreSQL 17; Flyway per-slice migrations; no `spring-boot-starter-web`/`webmvc`. Exact patch versions are fixed at M1 by the verification checklist.
4. Blocking JPA only on the dedicated DB executor sized to the Hikari pool; no reactive return types from JPA-transactional methods; explicit principal; DTO mapping inside transactions; commit-then-reject via outcome values where a rejected request must keep its side effects.
5. `watchspace` owns the room lane, in-memory state, sequence allocation and ledger; `realtime` is transport only; module dependencies are acyclic.
6. Mutation order: validate → persist + commit (guarded) → apply → broadcast. One canonical microsecond `mutationInstant` per mutation; `ROOM_ENDED` is the final ledger event. No `MAX+1` sequences. No heartbeat persistence.
7. One pinned timeline version and pinned subtitle tracks per room session.
8. Structured, spoiler-safe retrieval; LLM off-lane, bounded, deduplicated, time-limited; citation validation is not a factual-accuracy guarantee.
9. Only pre-authored, approved variations; selection by votes, host choice or deterministic locale rules.
10. Proof via REST collection, Java/JUnit scripted WebSocket clients, automated tests, logs and measured performance.
11. No Redis, Kafka, MongoDB, vector database or multi-instance support.
12. Small, tested vertical slices; no silent architecture changes.

---

## 14. Deliberate deviations from the PRD

Each deviation keeps the PRD's required functionality. [PRD §13.2: document deviations and reasoning]

| ID | PRD | This design | Reason |
|---|---|---|---|
| D-01 | Positions in seconds (`positionSeconds`, `tsSeconds`, `ts`, `from`/`to`, `currentTs`) | Integer ms internally and in all WebSocket inputs. Seconds kept for the timeline query params and as read-only `…Seconds`/`ts` companion fields in outputs. `currentTs` accepted but advisory. | Avoid float drift and unit mixing. |
| D-02 | Client emits `room.playback.update` | Client sends **`room.playback.command`** (intent); server emits **`room.playback.update`** (authoritative result). Only the current host's token may issue commands. | Separates inbound intent from authoritative state; the PRD security rule is preserved. |
| D-03 | Playback state values `play`/`pause`/`seek` | `state ∈ {PLAYING, PAUSED, BUFFERING}` plus `action ∈ {PLAY, PAUSE, SEEK, …}` | Seek is an action, not a resting state; the PRD buffering state is supported. |
| D-04 | `ai/ask` body carries `userId` and `currentTs` | Identity from JWT; a mismatched `userId` → 403; position from server state; `currentTs` advisory only. | PRD §10.1 forbids trusting caller identity; authoritative time is server-side. |
| D-05 | Lower-case enum values in examples | `UPPER_SNAKE` output; case-insensitive input. | Consistency. |
| D-06 | Presence `action: joined\|left` | `JOINED`, `LEFT`, `REMOVED`, `DISCONNECTED`, `RECONNECTED`, `QUALITY` | Reconnect semantics and connection quality. [PRD §6.1, §10.3] |
| D-07 | `users.role ∈ {viewer, host, admin}` | Global `ADMIN`; `HOST`/`VIEWER` per room. | A user can host one room and view another. |
| D-08 | `variation_options.vote_count` | Derived from `variation_votes`; counts hidden until close. | Prevents count drift and races. |
| D-09 | `interactions.rating` | Separate `title_ratings`; interactions derived from the ledger. | Ratings exist outside rooms. |
| D-10 | §9.4 suggests batching chat writes if volume is high | Individual writes; batching only after measurement. | Durability before broadcast; the PRD makes batching conditional. |
| D-11 | Two browser sessions / screen recordings | Java/JUnit scripted WebSocket clients and transcripts. | Track B. [PRD §4.3] |
| D-12 | "Non-engineer-friendly upload form" | Authenticated multipart upload endpoint with field-level validation errors. | Track B has no UI. |
| D-13 | Subtitle tracks as swappable assets | Seeded tracks; no subtitle-upload API in the MVP. | Scope; runtime localization still fully works. |
| D-14 | Trivia described as "AI-generated" context | Trivia text is authored metadata delivered verbatim; the model is used only for Q&A phrasing. | Accuracy and latency; still "surfaced by the AI engine". |
| D-15 | §9.1 "model room state as a Sink/Flux per room" | Per-room serialized lane with an immutable state reference; Reactor used for transport and fan-out. | Strict ordering with blocking JPA persistence. |

---

## 15. Requirements coverage matrix (v0.4)

Status: **Covered** = designed and testable; **Covered (deviation)** = covered with a §14 deviation.

| # | PRD requirement | Design | Status |
|---|---|---|---|
| E1.1 | Room creation, invite link/code, join flow, capacity limits | §4.2, §6.4 | Covered |
| E1.2 | Authoritative playback state machine broadcast to all | §4.1, §7.4 | Covered (D-02, D-03) |
| E1.3 | Drift reporting and gentle micro-seek correction | §4.4 | Covered |
| E1.4 | Chat with typing, reactions, persisted history | §4.6 | Covered |
| E1.5 | Presence, connection quality, reconnect/resync | §4.2, §4.4, §7 | Covered (D-06) |
| E1.6 | Mute, remove, lock, transfer host | §4.2 | Covered |
| E1-AC1 | Mid-playback joiner within 250 ms in 2 s | §4.4, §10 | Covered (measured) |
| E1-AC2 | Pause/seek on all clients within 1 s | §7.6, §10 | Covered (measured) |
| E1-AC3 | Ordered, persisted chat retrievable on reconnect | §4.5, §4.6 | Covered |
| E1-AC4 | Deterministic, tested host-disconnect behaviour | §4.3 | Covered |
| E2.1 | JSON/YAML timeline ingestion (scenes, characters, trivia, glossary, variation points) | §4.7, §5.4 | Covered |
| E2.2 | Trivia at markers, toggleable by host | §4.7, §4.11 | Covered |
| E2.3 | RAG Q&A over current and preceding timestamps with source explanation | §4.8 | Covered |
| E2.4 | Versioned swappable subtitles; alternate phrasing by viewer locale | §4.9 (incl. track integrity R11–R13) | Covered (D-13) |
| E2.5 | Timed group vote, tally, apply at correct point | §4.10 | Covered |
| E2-AC1 | Trivia within 1 s for ≥ 95 % of markers | §4.7, §10 | Covered (measured) |
| E2-AC2 | Answers cite entries; no fabrication | §4.8 | Covered (citations enforced; accuracy measured by QA, not guaranteed) |
| E2-AC3 | Subtitle swap does not interrupt or desync | §4.9 | Covered (tested: no `stateVersion` change) |
| E2-AC4 | Vote result identical and simultaneous for all | §4.10 | Covered |
| E3.1 | Dashboard: recent, upcoming, quick-rejoin | §4.2, §9.3 | Covered |
| E3.2 | Hybrid recommendations (ratings, completion, genre, co-watch) | §9.1–9.2 | Covered |
| E3.3 | Per-room analytics for past rooms | §9.3, §9.4 | Covered |
| E3.4 | Authenticated metadata upload | §4.7, §6.6 | Covered (D-12) |
| E3-AC1 | Recommendations update without redeploy | §9.2 | Covered |
| E3-AC2 | Analytics reconcile with the event log | §9.4, ADR-005 (canonical instants) | Covered (exact oracle query + controllable-clock fixture) |
| E3-AC3 | Schema validation with clear errors | §4.7 | Covered |
| P5.1 | Host: pin AI fact, on-demand trivia, approve localized variant | §4.7, §4.8, §4.10 | Covered |
| P5.2 | Participant: time-sync handshake before playback | §4.4 | Covered |
| P5.3 | AI only selects pre-approved variations | ADR-007 | Covered |
| A7.1 | Five-layer architecture; AI engine separate from realtime | ADR-001 | Covered |
| ST8 | Java option: Spring Boot, WebFlux WebSockets, JPA/Hibernate, Spring Security + JWT, PostgreSQL [PRD §8–9] | ADR-002, ADR-003 | Covered (Spring Boot 4.1.2; exact versions verified at M1) |
| A7.2 | Six core entities; normalized timeline with range index | §5 | Covered |
| A7.3 | Mandatory endpoints (create room, timeline range, ai/ask) | §6.3–6.5 | Covered (D-01, D-04, D-05) |
| A7.4 | WebSocket envelope and core events | §7 | Covered (D-01–D-03, D-06) |
| A7.5 | Object storage for variant assets | §4.9, §11 | Covered (local directory) |
| S10.1 | JWT ≤ 1 h with refresh flow | §8.1–8.2 | Covered (rotation; replay revocation committed before 401) |
| S10.2 | RBAC viewer/host/admin server-side | §8.6 | Covered (D-07) |
| S10.3 | WebSocket auth at handshake + re-validation | §7.1 | Covered |
| S10.4 | Validation/sanitization; no stored XSS | §8.3–8.4 | Covered |
| S10.5 | Secrets via environment only | §8.8 | Covered |
| N10.1 | Latency, drift, fan-out targets measured | §10 | Covered (measured, not assumed) |
| N10.2 | Many concurrent rooms; no single-room assumption | ADR-005 | Covered (single-instance limitation documented) |
| R10.1 | Structured logs; correlation ID per watch session | §11 | Covered |
| R10.2 | Reconnect without lost chat or duplicate join | §4.2, §4.6 | Covered |
| R10.3 | Health checks for API and real-time gateway | §11 | Covered |
| T4.3 | Track B proof via tests, Postman, scripted WS clients | §10 | Covered (D-11) |
| T12 | Tests for state machine, AI retrieval, RBAC; docs; ER; seed; retrospective | §10, §12 | Covered |
| C14 | Single machine; sample assets; no generative variations; fixed 4 weeks | §1, ADR-007, §12 | Covered |
| L3 | Learning outcome: relational vs document modelling trade-offs | — | Not designed (deferred O5; documentation only) |

---

## 16. Deferred optional enhancements (recorded, not implemented)

| ID | Enhancement | Trigger to reconsider |
|---|---|---|
| O1 | Exact next-marker scheduling instead of the 250 ms tick | Trivia lateness measurements |
| O2 | Automated architecture tests (ArchUnit or Spring Modulith) | Needs approval (new test dependency) |
| O3 | BlockHound in tests | Needs approval (new test dependency) |
| O4 | Purge job for expired/revoked refresh tokens | Table growth |
| O5 | ADR comparing relational vs document models (PRD learning outcome) | Week 4 documentation |
| O6 | Denormalize `watch_space_id`, `sequence_no` onto `chat_messages`; `citext` email | History query measurements |
| O7 | `Idempotency-Key` header for all REST POSTs | Client retry needs |
| O8 | Rename `watched_seconds` → `watched_ms` | Unit consistency clean-up |
| O9 | Chat write batching | Load test shows DB bottleneck |
| O10 | Browser-compatible WebSocket auth (ticket/subprotocol) | Only if a UI track is added |
| O11 | CHECK: rooms past `SCHEDULED` must have a pinned version | Low cost; schedule with a later migration |
| — | Redis/Kafka/vector DB/multi-instance | Out of scope; would need an approved ADR |

---

## 17. Open decisions

| ID | Decision | Default if not decided |
|---|---|---|
| ~~OD-1~~ | **Resolved in v0.3:** Spring Boot 4.1.2 on Java 21 with Jackson 3, Hibernate 7 and json-schema-validator 3.0.x (ADR-002). Remaining unverified details are M1 verification items M1-V1…V13, not open decisions. | — |
| OD-2 | LLM provider and model (hosted API vs local model) and its credentials | Provider-agnostic adapter with an OpenAI-compatible HTTP client configured by environment; a deterministic stub provider for tests. |
| OD-3 | Seed content: sample video source/licence and the second subtitle locale | One self-made or openly licensed short clip; `en` + `en-IN`. |
| OD-4 | PostgreSQL minor version and the Testcontainers image tag | Latest PostgreSQL 17 minor at M1, same tag for Compose and tests. |

---

## Appendix A — Audit finding traceability

| Finding | Resolution |
|---|---|
| C1 | ADR-001 module ownership; ADR-005 lane for all REST/WS/timer mutations |
| C2 | §4.8 retrieval sets A/B/C, spoiler exclusion |
| C3 | `trivia_enabled`, §4.7, §4.11, `room.settings.update` |
| C4 | §4.9 per-viewer alternates, `room.subtitle.variants`, precedence rules |
| C5 | §4.4 connection quality, presence `QUALITY` |
| C6 | `title_ratings`, `PUT /titles/{id}/rating`, interactions derived only |
| I1 | §14 deviations D-01…D-05; §6.5 `ai/ask` compatibility fields |
| I2 | §4.7 on-demand trivia, §4.8 pins, §4.10 host selection and suggestions |
| I3 | §4.8 structured output, `sources[]`, explanation, honesty note |
| I4 | §6.2 `/me/watch-spaces`, §4.2 quick-rejoin, §9.3 |
| I5 | §11 `sessionCorrelationId`, `realtime` health indicator |
| I6 | Composite FKs in `DATABASE_SCHEMA.sql` (verified with negative tests) |
| I7 | `host_user_id` single source of truth; `room_role` column removed |
| I8 | `raw_document`, `validation_status`, upload validates immediately, contract fields |
| I9 | `room_subtitle_tracks`, cue keys, seeded tracks |
| I10 | `watch_space_moderation`; removed users cannot rejoin; mute persists |
| I11 | Closed event-type catalog (CHECK + §4.6) |
| I12 | ADR-005 ordering; §5.2 transaction table; join row lock |
| I13 | §4.12 interval-union derivation; 0.90 configurable threshold |
| I14 | §4.1 transition tables |
| I15 | §4.3 promotion among connected members |
| I16 | §7.1 token renewal; §7.5 close codes; §7.6 slow consumer |
| I17 | §4.13 recovery, idle auto-end, shutdown |
| I18 | §4.8 LLM off-lane, concurrency limit, timeouts, dedupe |
| I19 | §4.10 cancel-on-seek, stays applied, counts hidden, deterministic ties |
| I20 | §4.8 verbosity table; independent of trivia |
| I21 | §4.2 HMAC invite codes, rotation, join rate limits, PUBLIC_LINK semantics |
| I22 | §8.3 plain-text policy; §8.4 safe parsing; §8.5 BCrypt; §8.1 JWT key rules |
| I23 | No I23 finding was raised in the audit (refresh-token reuse detection was listed as optional O4). Replay rejection is now required by approval decision 12 (§8.2); only the purge job remains deferred (O4). |
| I24 | ADR-003 execution rules |
| I25 | ADR-002 version table (Spring Boot 4.1.2 since v0.3) and M1 verification checklist |
| I26 | ADR-001 `infrastructure` scope; module ownership table |

## Appendix B — M0.1 review revision (v0.2 → v0.3)

| Review item | Change | Where |
|---|---|---|
| 1. Resolve OD-1 | Spring Boot 3.5.16 → **4.1.2** on Java 21. Jackson 3 only (Jackson 2 banned), Hibernate 7, json-schema-validator 3.0.x, Boot 4 modular starters (`spring-boot-starter-flyway`, `spring-boot-starter-security-oauth2-resource-server`, `*-test` starters). Facts verified from official sources are listed separately from the M1 verification checklist (M1-V1…V13). OD-1 closed. | ADR-002, ADR-003 §7, §8.1, §8.4, §12 M1, §13, §17 |
| 2. Deterministic analytics | One microsecond-truncated, non-decreasing `mutationInstant` per lane mutation, used for every timestamp the mutation writes. `started_at`/`ended_at` equal the `ROOM_STARTED`/`ROOM_ENDED` `occurred_at`. `ROOM_ENDED` is the final ledger event. Exact metric definitions, an oracle SQL query and an exact-equality test with a controllable clock. Schema: `room_events.occurred_at` has no default; at most one `ROOM_CREATED`/`ROOM_STARTED`/`ROOM_ENDED` per room; rule R9. | ADR-005, §4.12, §4.13, §5.2, §9.4, `DATABASE_SCHEMA.sql` |
| 3. Refresh replay outcome | Commit-then-reject rule: the service returns a sealed `RefreshOutcome` and never throws for replay; the family revocation commits before the adapter returns `401 REFRESH_TOKEN_REUSED`; a failed revocation yields `503`, never `401`. Three required M2 tests. Rule R10. | ADR-003 §4, §5.2, §8.2, §12 M2, `DATABASE_SCHEMA.sql` |
| 4. Status | "Approved baseline" → **"ready for user approval"**; M1 still requires explicit authorization. | Header, §12, §13, `DATABASE_SCHEMA.sql` header, `CLAUDE.md` |
| 5. Consistency | Version references, decision records, coverage matrix (E3-AC2, S10.1, new ST8 row) and `CLAUDE.md` guardrails aligned. | Throughout |

## Appendix C — M0.2 documentation correction (v0.3 → v0.4)

| Correction | Change | Where |
|---|---|---|
| 1. Flyway PostgreSQL module | `org.flywaydb:flyway-database-postgresql` is a **mandatory** dependency (BOM-managed version). M1-V2 now verifies dependency resolution and successful migrations against the selected PostgreSQL 17 image; it no longer decides whether the module is needed. | ADR-002, `CLAUDE.md` |
| 2. Subtitle-track integrity | `SUBTITLE_TRACK_SWAP` options must reference a track of the same title (application rule R11, checked at validation/publish) with the same locale (composite FK `(subtitle_track_id, locale)`, `locale` required). Room start pins only the `ACTIVE` track per locale of the room's title (R12). Pinned tracks, their cues and assets remain usable until the room ends (R13). Eligibility rule and five required tests. | §4.9, §15 E2.4, `DATABASE_SCHEMA.sql` |

## Appendix D — M1 corrections (recorded during M1, 2026-10-26)

Found while running the M1 verification checklist. None changes a PRD requirement, the database design, API paths, event names or units.

| Finding | Change | Where |
|---|---|---|
| 1. YAML aliases (M1-V7) | Jackson 3 does not expand YAML aliases and ignores `maxAliasesForCollections`. Documents containing any alias are rejected, which is stricter than the previous "≤ 20 aliases". `LoaderOptions` → snakeyaml-engine `LoadSettings`; nesting through `StreamReadConstraints`. | §8.4 |
| 2. Unexpected-error code | The error catalogue had no code for HTTP 500. Added `INTERNAL_ERROR` (generic message, no internal details). Unlisted framework 4xx statuses keep their status with `MALFORMED_REQUEST`. | §6.1 |
| 3. Context propagation | `io.micrometer:context-propagation` is not transitive in Boot 4.1.2; declared explicitly (BOM-managed). | ADR-002 starters |
| 4. PostgreSQL image | OD-4 default applied: `postgres:17.11` for Compose and Testcontainers. | ADR-002 |
| 5. `db` health indicator | Boot's blocking `DataSourceHealthIndicator` would run JDBC on a shared Reactor scheduler; it is replaced by a reactive `db` indicator on the `db-*` executor (ADR-003 §1–§2). | Implementation only |
