# M2 verification report — Flyway V1 and authentication

Date: 2026-10-26. Environment: OpenJDK 21.0.12.1, Maven 3.9.11 (wrapper), Docker Engine 29.9.2,
PostgreSQL image `postgres:17.11` (Testcontainers and Compose). No dependency was added or changed in M2;
all resolved versions are as recorded in [`dependency-versions.md`](dependency-versions.md)
(Spring Boot 4.1.2, Spring Security 7.1.2, Nimbus JOSE 10.9.1, Hibernate 7.4.13, Flyway 12.4.0, pgjdbc 42.7.14).

## Result

`./mvnw clean verify`: enforcer 5/5 rules passed; **49 unit tests and 62 integration tests passed, 0 failed,
0 skipped**. Docker Compose (`scripts/compose-check.sh`) verified separately.

## What M2 delivers

| Area | Implementation |
|---|---|
| Flyway V1 | `V1__auth_users.sql`: `app_users`, `refresh_tokens` and their indexes, copied verbatim from `DATABASE_SCHEMA.sql`. |
| Endpoints | `POST /api/v1/auth/register` (201 `{userId}`), `login`, `refresh`, `logout` (204), `GET/PATCH /api/v1/me`. |
| Access tokens | HS256 JWT; key from `JWT_SIGNING_KEY` (base64, ≥ 256 bits); claims `iss`, `aud=ai-watch-spaces`, `sub`, `sys_role`, `iat`, `exp`, `jti`; TTL 15 min default, > 60 min rejected at startup. |
| Validation | HS256 only; `typ` JWT; issuer; audience; `exp`/`nbf` with 30 s skew; required `sub` (UUID), `sys_role` (USER/ADMIN), `iat`, `jti`. Expired → `401 TOKEN_EXPIRED`; other failures → `401 UNAUTHENTICATED`; `WWW-Authenticate: Bearer`. |
| Roles | `sys_role` → `ROLE_USER`/`ROLE_ADMIN`. Admin endpoints (`/actuator/**` except public health/info) also re-read `system_role` from the database on every call. |
| Refresh tokens | 256-bit opaque, SHA-256 hex stored, 14-day TTL, new family per login, `SELECT … FOR UPDATE` rotation, replay → family revoked and committed before `401 REFRESH_TOKEN_REUSED`; failed revocation → `503 SERVICE_BUSY`. No `noRollbackFor`/`REQUIRES_NEW`. |
| Passwords | `DelegatingPasswordEncoder`, BCrypt strength 12 (`{bcrypt}$2a$12$…`), 12–128 characters and ≤ 72 UTF-8 bytes; hashing on Reactor `parallel`, not on Netty or `db-*`. |
| Rate limits | In-memory token buckets in `common`: login 5/min per email and 20/min per IP; register 5/hour per IP; `429 RATE_LIMITED` with computed `Retry-After`. |
| Input policy | Display name: NFC + trim, no controls, ≤ 20 line breaks, no markup, 1–80 code points. Locale: BCP 47, canonicalized. Unknown JSON properties rejected. |

Behaviour the design left open is recorded in SYSTEM_DESIGN **Appendix E** (written before implementation).

## Test evidence

| Requirement | Test(s) | Result |
|---|---|---|
| V1 equals the target schema slice (tables, columns, constraints, indexes) | `V1MigrationMatchesTargetSchemaIT` | Passed |
| M1 probe migration kept out of the application schema | `ApplicationContextIT.m1ProbeMigrationIsKeptOutOfTheApplicationSchema` | Passed |
| Register/login/`/me`, BCrypt-12 hash stored, email normalization, duplicate → 409 | `AuthFlowIT` | Passed |
| 400 validation (email, password chars/bytes, display-name markup/length, unknown property, locale) | `AuthFlowIT` | Passed |
| Generic `INVALID_CREDENTIALS` (wrong password, unknown user, over-long password) | `AuthFlowIT.loginFailuresAreGeneric` | Passed |
| Valid, missing, expired, within-skew, not-yet-valid, wrong issuer, wrong audience, missing/malformed claims, wrong key, tampered, `alg=none`, garbage, wrong `typ` | `AccessTokenValidationIT` (11 tests) | Passed |
| USER → 403; ADMIN claim without DB ADMIN → 403; ADMIN → 200; demotion immediate; refresh re-reads role | `AdminAccessIT` | Passed |
| Rotation within a family; new family per login; only hashes stored | `RefreshTokenIT` | Passed |
| **§8.2 required 1:** after `401 REFRESH_TOKEN_REUSED`, a separate DB read shows every family token revoked | `RefreshTokenIT.replayRevokesTheWholeFamilyAndTheRevocationIsCommitted` | Passed |
| **§8.2 required 2:** the newest token of the family fails afterwards | `RefreshTokenIT.newestTokenFailsAfterReplay` | Passed |
| **§8.2 required 3:** fault injected into the revocation `UPDATE` (PostgreSQL trigger) → `503`, not `401`, and no token row changes | `RefreshTokenIT.failedRevocationIsNot401AndLeavesTokenStateUnchanged` | Passed |
| Concurrent use of one token: exactly one 200 and one 401; whole family revoked | `RefreshTokenIT.concurrentUseOfOneTokenRotatesOnceAndRevokesTheFamily` | Passed |
| Unknown/expired refresh → `REFRESH_TOKEN_INVALID`, nothing changes; logout semantics and ownership | `RefreshTokenIT` | Passed |
| Missing/short/non-base64 key fails startup; TTL 61 min fails, 60 min starts; key never in messages | `AuthStartupValidationTest` | Passed |
| Rate limits with production defaults | `RateLimitIT`, `RateLimiterTest` | Passed |
| No passwords, tokens, emails, hashes or signing key in logs | `NoSecretsInLogsIT` | Passed |
| All auth DB work on `db-*` threads (register, login, refresh, replay, `/me`, admin re-check) | `BlockingBoundaryIT.authFlowsAcquireConnectionsOnlyOnDbThreads` | Passed |

**Docker Compose** (`postgres:17.11`, random 256-bit key in an uncommitted `.env`): liveness/readiness UP;
Flyway history `1 | V1__auth_users.sql | t`; register 201 → login → `/me` 200 → refresh 200 → replay of the
same refresh token 401 `REFRESH_TOKEN_REUSED`. The jar with an empty `JWT_SIGNING_KEY` exits with
"JWT_SIGNING_KEY is missing".

## M1-V3 status

**Passed in M2.** The pass condition ("auth integration tests pass with HS256 tokens") is met by
`AccessTokenValidationIT`, `AuthFlowIT`, `AdminAccessIT` and `RefreshTokenIT`.

## Findings during M2

- **Spring Security fills in a missing `iat`.** `MappedJwtClaimSetConverter.withDefaults` adds `iat` when a
  token has none, so a "required `iat`" check never fires. The decoder now uses a claim-set converter that
  keeps `iat` absent; `AccessTokenValidationIT` covers it.
- Bean Validation's `@Email` rejects an address with leading/trailing spaces (`400 VALIDATION_FAILED`); the
  stored email is otherwise kept as sent and `normalized_email` is lower-cased.
- The test-only M1 probe migration now has its own schema and Flyway history table; Flyway logs one WARN in
  tests ("schema m1_probe already exists") because the probe SQL uses `CREATE SCHEMA IF NOT EXISTS`.

## Unresolved items and known limits

- **Postman collection**: not started (Week 4 deliverable per SYSTEM_DESIGN §12).
- **Health details** for ADMIN use the token role only; the database re-check applies to the other
  admin-only actuator endpoints (Appendix E #8).
- **Rate limits** are per process (single-instance assumption, §8.7) and key on the socket address; proxy
  headers are not trusted.
- **BCrypt load**: hashing runs on Reactor `parallel` (one thread per CPU). A login flood is bounded by the
  rate limits, not by a dedicated executor; this was not load-tested. No performance numbers are claimed.
- **Login timing**: unknown users run a dummy BCrypt check, but the database lookup still differs slightly;
  timing equality was not measured.
- **`PATCH /me` locale change** does not yet notify sockets (`room.subtitle.variants`); there are no sockets
  until M5 (Appendix E #9).
- **Refresh-token purge** of old rows remains deferred (O4).
- **Spring Boot 4.1.x exact OSS end date** (2027-07-31) is still second-hand; see `dependency-versions.md`.
