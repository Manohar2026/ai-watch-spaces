# Claude Code Project Instructions — AI Watch Spaces Backend

Read `SYSTEM_DESIGN.md` (v0.4) and `DATABASE_SCHEMA.sql` (v0.4) before proposing or changing architecture. They are the design baseline for this backend-only project, **approved by the user on 2026-10-26**. `DATABASE_SCHEMA.sql` is the target state, not a migration.

**Current checkpoint:** design v0.4 approved; **M1 is authorized (M1 only)**. M1 code may be written only in an environment where the M1 checks can actually run (Maven Central and Docker Hub reachable, Docker daemon available). Stop at the end of M1 and report. **Do not start M2 or any later milestone without the user's explicit authorization.**

## Guardrails

### Scope and architecture
1. Build **backend-only** (PRD Track B). Do not create React, templates, browser UI or frontend assets.
2. One Java/Spring Boot modular monolith. No microservices or separate deployables.
3. Stack is pinned (SYSTEM_DESIGN ADR-002):
   - Java 21; Spring Boot **4.1.2** parent; Maven (wrapper).
   - Spring WebFlux (REST + plain-JSON WebSockets, no STOMP).
   - Spring Security 7 with JWT via `spring-boot-starter-security-oauth2-resource-server` (Nimbus, HS256).
   - Spring Data JPA with **Hibernate 7**; PostgreSQL 17; `spring-boot-starter-flyway` **plus the mandatory `org.flywaydb:flyway-database-postgresql`** (BOM-managed version; M1-V2 verifies resolution and migrations against the PostgreSQL 17 image).
   - **Jackson 3 only** (`tools.jackson.*`): never add Jackson 2 `jackson-databind` or the deprecated `spring-boot-jackson2` module.
   - `com.networknt:json-schema-validator` **3.0.x** (Jackson 3 line), pinned to an exact patch; never 2.x.
   - Boot 4 modular test starters (`spring-boot-starter-webflux-test`, `-data-jpa-test`, `-security-test`) plus `spring-boot-testcontainers`.
   - Never override Spring BOM-managed versions individually.
3a. **M1 verification checklist (ADR-002, M1-V1…V13).** Exact managed versions (Hibernate, Jackson, Flyway, Testcontainers, JDBC driver) and several Boot 4.1 / Jackson 3 API details were **not** verified in M0.1. Verify each item at M1, record resolved versions in `docs/dependency-versions.md`, and report every item as passed, failed or resolved by its documented fallback. Never claim an unverified item was tested.
4. **Never add `spring-boot-starter-web` or `spring-boot-starter-webmvc`.** Spring MVC on the classpath switches the app away from WebFlux/Netty.
5. PostgreSQL is the system of record. Do not add MongoDB, Redis, Kafka, a vector database or any other datastore/search/broker unless the user approves an ADR first.
6. Module boundaries: `auth`, `users`, `catalog`, `timeline`, `watchspace`, `realtime`, `playback`, `chat`, `ai`, `variations`, `recommendations`, `analytics`, `admin`, `common`, `infrastructure`.
   - Follow the ownership table and the **acyclic dependency graph** in SYSTEM_DESIGN ADR-001.
   - `watchspace` owns the per-room mutation lane, in-memory room state, sequence/stateVersion allocation and the `room_events` ledger.
   - `realtime` is WebSocket transport only (handshake, sessions, fan-out); it implements `RoomBroadcaster`.
   - `playback` is pure domain code (no Spring, no DB).
   - `infrastructure` holds cross-cutting technology only and owns no feature entities/repositories.
   - Modules call each other only through public application-service interfaces and DTOs, never through another module's entities or repositories. `watchspace` reaches other modules only through the SPIs it defines (`RoomBroadcaster`, `RoomTickHandler`, `RoomLifecycleListener`).

### WebFlux + blocking JPA (ADR-003)
7. No JDBC/JPA or other blocking work on Netty event-loop threads. Run it on the single dedicated `db-*` executor whose thread count equals the Hikari maximum pool size. Do not use the default `boundedElastic` scheduler for JDBC. Wrap blocking services once at the adapter edge (`Mono.fromCallable(...).subscribeOn(dbScheduler)`); do not scatter `subscribeOn`.
8. Methods that rely on JPA transactions return plain values/DTOs, **never `Mono`/`Flux`**. Transactions never span thread hops.
8a. **Commit-then-reject.** When a request must be rejected but its side effects must persist, the transactional method returns an outcome value instead of throwing, and the adapter maps the outcome to the error after commit. Refresh-token replay: `RefreshTokenService` returns `ReuseDetected`; the family revocation commits **before** the API returns `401 REFRESH_TOKEN_REUSED`. A failed revocation returns `503`, never `401`. Do not use `noRollbackFor` or `REQUIRES_NEW` for this.
9. Pass the authenticated principal explicitly into blocking services. Never read `SecurityContextHolder` in services.
10. Map entities to DTOs inside the transaction. `spring.jpa.open-in-view=false`. Never expose JPA entities from controllers or WebSocket handlers.
11. Timers never mutate room state directly; they submit commands to the room lane. LLM calls use reactive `WebClient`, are bounded and time-limited, and never run inside the room lane.

### Room mutations and persistence (ADR-005)
12. Every durable room mutation — REST, WebSocket or timer — goes through that room's lane, in this order: **validate → persist and commit (with the `last_sequence_no` optimistic guard) → apply to in-memory state → broadcast**.
    - On any failure, do not change memory and do not broadcast.
    - Never allocate sequence numbers with `MAX(sequence_no)+1`.
    - Read the injected `Clock` **once** per mutation, truncate to microseconds, and clamp so it is never earlier than the room's previous instant. Use that single `mutationInstant` for every timestamp the mutation writes (all `room_events.occurred_at`, `started_at`, `ended_at`, `joined_at`, `left_at`, …). `room_events.occurred_at` has no DB default and must always be supplied.
    - `ROOM_ENDED` is the room's final ledger event; reject any later append.
    - Analytics are computed only from the ledger in `sequence_no` order and must equal the oracle query in SYSTEM_DESIGN §9.4 exactly.
13. Do not persist playback heartbeats, sync reports, typing, reactions or presence-quality updates. Persist the closed event-type catalog, chat, AI Q&A results/sources, votes, applications, interactions, ratings and end-of-session analytics.
14. Use integer milliseconds internally and on all WebSocket inputs. Seconds appear only in the timeline REST query parameters (`from`/`to`), the advisory `currentTs`, and read-only `…Seconds`/`ts` output companion fields. Convert at the boundary; never mix units.

### Security
15. Identity comes only from the JWT. A supplied `userId` that differs from the token subject is rejected (`403 USER_MISMATCH`). Authoritative playback time always comes from room server state.
16. Global `ADMIN` is separate from room roles. `watch_spaces.host_user_id` is the only source of truth for HOST. Re-check membership, removal, mute and host status on every sensitive WebSocket command, not only at handshake.
17. Follow SYSTEM_DESIGN §8 exactly:
    - Plain-text input policy (markup rejected).
    - Safe YAML/JSON parsing to trees only.
    - BCrypt strength 12 via `DelegatingPasswordEncoder` (≤ 72-byte passwords).
    - HS256 key ≥ 256 bits from the environment; access TTL ≤ 60 min (default 15).
    - Refresh-token rotation with family revocation on replay, committed before the 401 (guardrail 8a).
    - HMAC-hashed invite codes; rate limits; WebSocket token renewal, close codes and slow-consumer policy.
18. Never commit real secrets. `JWT_SIGNING_KEY`, `INVITE_CODE_PEPPER`, DB credentials and `LLM_API_KEY` come from environment variables; `.env.example` holds placeholders only. Never log passwords, tokens, invite codes or API keys.

### AI and variations
19. AI answers must be grounded in retrieved timeline events from the room's pinned version, never use future entries, and cite validated source IDs. Insufficient evidence returns the documented fallback. Do not claim citation validation proves factual correctness.
20. Subtitle tracks: a `SUBTITLE_TRACK_SWAP` option must reference a track of the same title and locale (R11, composite FK); room start pins only the `ACTIVE` track per locale of the room's title (R12); pinned tracks, cues and assets stay usable until the room ends (R13). Swap options whose track is not pinned in the room are ineligible (`409 OPTION_NOT_ELIGIBLE`). Implement the five tests in SYSTEM_DESIGN §4.9.
20a. Only pre-authored, approved variation options may be applied (vote, host selection, or deterministic locale rules). Model output must never create or alter subtitles, assets or narrative variations. Per-viewer locale alternates must not change playback state or `stateVersion`.

### Engineering practice
21. Use DTOs, Bean Validation, service-layer rules, the documented error envelope and codes, and structured logs that carry `correlationId` and `sessionCorrelationId`.
22. Flyway versioned migrations per feature slice (V1…V7 in `DATABASE_SCHEMA.sql`). Never use `ddl-auto=update`; runtime `none`, tests `validate`.
23. Every behavior change includes or updates tests. Prioritize: playback state machine, lane ordering/failure handling, capacity races, timeline validation, retrieval and citations, RBAC, refresh-token replay (committed revocation), votes, chat ordering, reconnect/host promotion, and analytics reconciliation (exact equality, controllable clock). Integration tests use Testcontainers PostgreSQL; scripted WebSocket clients use JUnit with the JDK `java.net.http.WebSocket`.
24. Do not claim performance targets are met without measured results. State test conditions and simulation limits.
25. Optional enhancements O1–O11 (SYSTEM_DESIGN §16) are deferred. Do not implement them, or add new libraries for them, without approval.

## Workflow for implementation requests

- Inspect the existing repository and identify the relevant modules and files before changing code.
- Implement one narrow vertical slice at a time, following the milestones in SYSTEM_DESIGN §12. Stop at the requested checkpoint.
- Do not silently change requirements, the database design, API paths, event names, error codes, units or deployment architecture. If a change is truly necessary, explain the trade-off and update SYSTEM_DESIGN.md (including §14 deviations and §15 coverage) **before** implementing.
- Avoid unrelated refactors and unnecessary abstractions. Prefer readable Java and explicit domain rules.
- After each slice, run the relevant tests and report exactly what passed, failed or could not be run.
- Keep the Postman collection, scripted WebSocket scenarios, seed data, migrations and docs synchronized with the implementation.
- Check SYSTEM_DESIGN §17 (open decisions) before work that depends on them; ask the user rather than deciding silently.

## Milestones M1–M7 (only when explicitly authorized)

| # | Deliverable |
|---|---|
| M1 | Maven scaffold on Spring Boot 4.1.2 / Java 21, module packages, Docker Compose, configuration, DB executor, error envelope, correlation, logging, health; complete the M1-V1…V13 verification checklist and record resolved versions; blocking-boundary test; Jackson 3 JSONB round-trip |
| M2 | Flyway V1 and authentication (register/login/refresh with committed replay revocation/logout, `/me`), including the three replay tests in SYSTEM_DESIGN §8.2 |
| M3 | Flyway V2, timeline JSON Schema v1, seed title/timeline/subtitles, catalogue and timeline read endpoints; test that cues and assets of `APPROVED`/`ACTIVE` tracks are immutable (changes only as a new version) and that seeded assets match their checksums (R13) |
| M4 | Flyway V3, room lane and ledger, create/join/leave/start/end, invites, lock, settings; capacity-race and ordering tests |
| M5 | WebSocket handshake, snapshot, presence, reconnect grace, token renewal, close codes |
| M6 | Playback commands with persist → apply → broadcast, tested with three scripted clients |
| M7 | Sync ping/pong, drift correction, connection quality, measured joiner sync |

Do not begin chat, AI, variations, recommendations or analytics until M7 passes.
