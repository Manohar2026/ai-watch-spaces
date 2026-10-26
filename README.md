# ai-watch-spaces

Backend for AI Watch Spaces (PRD Track B, backend only): one Spring Boot 4.1.2 / Java 21 modular
monolith on WebFlux + Netty, with PostgreSQL 17.

- Design baseline: [`SYSTEM_DESIGN.md`](SYSTEM_DESIGN.md) (v0.4) and [`DATABASE_SCHEMA.sql`](DATABASE_SCHEMA.sql) (target state).
- Resolved versions and the M1 verification results: [`docs/dependency-versions.md`](docs/dependency-versions.md).
- Status: **M2 complete** — M1 infrastructure plus Flyway V1 and authentication (register, login,
  refresh with replay detection, logout, `/me`). Results: [`docs/m2-verification.md`](docs/m2-verification.md).

## Requirements

- JDK 21 (the enforcer rejects other versions). Maven is provided by the wrapper (`./mvnw`).
- Docker with a running daemon (Testcontainers and Compose). Images used: `postgres:17.11`,
  `eclipse-temurin:21-jre`, `testcontainers/ryuk`.

## Build and test

```bash
./mvnw verify          # enforcer + unit tests (*Test) + integration tests (*IT, Testcontainers PostgreSQL 17)
./mvnw test            # unit tests only (no Docker needed)
```

## Run locally with Docker Compose

```bash
cp .env.example .env               # then replace every placeholder; .env is git-ignored
./mvnw -B package -DskipTests      # the Dockerfile copies target/ai-watch-spaces-*.jar
docker compose up -d --build
curl localhost:8080/actuator/health/readiness
```

`scripts/compose-check.sh` runs the same steps and prints health and the Flyway
history.

## Configuration

`src/main/resources/application.yml` holds defaults only. Secrets and environment-specific values come
from environment variables (see `.env.example`): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`DB_POOL_SIZE` (Hikari pool size and the `db-*` executor thread count; they must be equal),
`SERVER_PORT`, and **`JWT_SIGNING_KEY`** (required: base64 of at least 32 random bytes, e.g. `openssl rand -base64 32`;
the app refuses to start without it), `JWT_ISSUER`, `ACCESS_TOKEN_TTL` (default 15m, at most 60m).
`INVITE_CODE_PEPPER` and `LLM_API_KEY` are used from later milestones.

## API (M2)

Base path `/api/v1`. Public: `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`.
Bearer token required: `POST /auth/logout`, `GET /me`, `PATCH /me`. See SYSTEM_DESIGN §6.2 and Appendix E.

## Operations endpoints

| Endpoint | Access |
|---|---|
| `/actuator/health/liveness` | public |
| `/actuator/health/readiness` (groups `readinessState`, `db`, `realtime`) | public; component details for `ADMIN` only |
| `/actuator/info` | public |
| `/actuator/metrics` | `ADMIN` (token role and database role) |

Every response carries `X-Correlation-Id` (a supplied valid UUID is echoed, otherwise one is generated).
Errors use the envelope `{ code, message, correlationId, details[] }`.

## Spring Boot support window

Spring Boot 4.1.0 was released on 2026-06-10 ([announcement](https://spring.io/blog/2026/06/10/spring-boot-4/)),
and the [Spring support policy](https://spring.io/support-policy/) gives minor releases at least 13 months of
OSS support, so 4.1.x is supported until **at least July 2027**. The exact end date, 2027-07-31, comes from
secondary sources quoting Spring's generations feed and is not confirmed first-hand; see
`docs/dependency-versions.md`.
