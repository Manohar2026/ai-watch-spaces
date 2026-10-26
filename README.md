# ai-watch-spaces

Backend for AI Watch Spaces (PRD Track B, backend only): one Spring Boot 4.1.2 / Java 21 modular
monolith on WebFlux + Netty, with PostgreSQL 17.

- Design baseline: [`SYSTEM_DESIGN.md`](SYSTEM_DESIGN.md) (v0.4) and [`DATABASE_SCHEMA.sql`](DATABASE_SCHEMA.sql) (target state).
- Resolved versions and the M1 verification results: [`docs/dependency-versions.md`](docs/dependency-versions.md).
- Status: **M1 complete** (scaffold, configuration, db executor, error envelope, correlation IDs,
  structured logging, health). No feature endpoints or application migrations exist yet; they start at M2.

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

`scripts/m1-compose-check.sh [--with-probe]` runs the same steps and prints health and the Flyway
history. `--with-probe` also applies the test-only M1 probe migration.

## Configuration

`src/main/resources/application.yml` holds defaults only. Secrets and environment-specific values come
from environment variables (see `.env.example`): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`DB_POOL_SIZE` (Hikari pool size and the `db-*` executor thread count; they must be equal),
`SERVER_PORT`. `JWT_SIGNING_KEY`, `INVITE_CODE_PEPPER` and `LLM_API_KEY` are used from later milestones.

## Operations endpoints

| Endpoint | Access |
|---|---|
| `/actuator/health/liveness` | public |
| `/actuator/health/readiness` (groups `readinessState`, `db`, `realtime`) | public; component details for `ADMIN` only |
| `/actuator/info` | public |
| `/actuator/metrics` | `ADMIN` |

Every response carries `X-Correlation-Id` (a supplied valid UUID is echoed, otherwise one is generated).
Errors use the envelope `{ code, message, correlationId, details[] }`.

## Spring Boot support window

Not confirmed at M1: the spring.io support table could not be read from the build environment.
Published Spring calendars list OSS support for 4.1.x until 2027-07; check
<https://spring.io/projects/spring-boot#support> before relying on it.
