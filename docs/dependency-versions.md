# Resolved dependency versions and M1 verification (M1-V1…V13)

Recorded at M1 on 2026-10-26 from `./mvnw dependency:list`, `dependency:tree`, `help:effective-pom` and
`enforcer:enforce`, all run against Maven Central. Every version below is managed by the Spring Boot
4.1.2 BOM unless marked **pinned**. No BOM-managed version is overridden.

## Environment used for verification

| Item | Value |
|---|---|
| JDK | OpenJDK 21.0.12.1 |
| Maven | 3.9.11 (via Maven Wrapper, `.mvn/wrapper/maven-wrapper.properties`) |
| Docker | Engine 29.9.2, Compose v5.6.1 (daemon started manually in the cloud sandbox; cgroup v1) |
| PostgreSQL image | `postgres:17.11` (digest `sha256:b8faeff7712663c2773a190181e1246e37e364b887611c04658c28b44e2408fe`, server reports 17.11). Same tag in `compose.yaml` and `TestcontainersConfiguration` (OD-4). |
| Testcontainers helper image | `testcontainers/ryuk:0.14.0` |
| App runtime image | `eclipse-temurin:21-jre` |

## Resolved versions

| Component | Coordinates | Version |
|---|---|---|
| Spring Boot | `org.springframework.boot:spring-boot-starter-parent` | **4.1.2** |
| Spring Framework | `org.springframework:spring-core`, `spring-webflux`, `spring-orm`, `spring-jdbc`, `spring-tx` | 7.0.10 |
| Spring Security | `org.springframework.security:spring-security-*` (core, web, config, oauth2-jose, oauth2-resource-server) | 7.1.2 |
| Spring Data | `org.springframework.data:spring-data-jpa`, `spring-data-commons` (BOM 2026.0.2) | 4.1.2 |
| Hibernate ORM | `org.hibernate.orm:hibernate-core` | 7.4.13.Final |
| Hibernate Validator | `org.hibernate.validator:hibernate-validator` | 9.1.5.Final |
| Jakarta Persistence / Validation | `jakarta.persistence-api` / `jakarta.validation-api` | 3.2.0 / 3.1.1 |
| Jackson 3 | `tools.jackson.core:jackson-core`, `jackson-databind`; `tools.jackson.dataformat:jackson-dataformat-yaml` (BOM 3.1.7) | 3.1.7 |
| Jackson annotations | `com.fasterxml.jackson.core:jackson-annotations` (used by Jackson 3; expected) | 2.21 |
| YAML engine (Jackson 3 YAML backend) | `org.snakeyaml:snakeyaml-engine` | 3.0.1 |
| SnakeYAML (Spring Boot config loading only) | `org.yaml:snakeyaml` | 2.6 |
| Flyway | `org.flywaydb:flyway-core` | 12.4.0 |
| Flyway PostgreSQL module | `org.flywaydb:flyway-database-postgresql` (no version in POM; BOM `${flyway.version}`) | 12.4.0 |
| PostgreSQL JDBC | `org.postgresql:postgresql` | 42.7.14 |
| HikariCP | `com.zaxxer:HikariCP` | 7.0.2 |
| Reactor | `io.projectreactor:reactor-core` (BOM 2025.0.8) | 3.8.8 |
| Reactor Netty | `io.projectreactor.netty:reactor-netty-http` | 1.3.8 |
| Netty | `io.netty:netty-*` | 4.2.19.Final |
| Micrometer | `io.micrometer:micrometer-core` | 1.17.2 |
| Micrometer context propagation | `io.micrometer:context-propagation` (declared explicitly, version BOM-managed) | 1.2.1 |
| Nimbus JOSE + JWT | `com.nimbusds:nimbus-jose-jwt` | 10.9.1 |
| JSON Schema validator | `com.networknt:json-schema-validator` (**pinned**, not in the Boot BOM) | **3.0.8** |
| Logback / SLF4J | `ch.qos.logback:logback-classic` / `org.slf4j:slf4j-api` | 1.5.38 / 2.0.20 |
| Testcontainers | `org.testcontainers:testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`, `testcontainers-jdbc` | 2.0.5 |
| JUnit | `org.junit.jupiter:junit-jupiter-api` | 6.0.3 |
| reactor-test | `io.projectreactor:reactor-test` (transitive via `spring-boot-starter-webflux-test`) | 3.8.8 |
| AssertJ | `org.assertj:assertj-core` | 3.27.7 |
| Maven Enforcer / Surefire / Failsafe | plugin versions from the Boot parent | 3.6.3 / 3.5.6 / 3.5.6 |

## M1 verification checklist results

Evidence names refer to tests under `src/test/java`. "Passed" means the stated pass condition was
executed in this environment and succeeded.

| ID | Result | Evidence and notes |
|---|---|---|
| M1-V1 | **Passed** | Parent 4.1.2 resolved from Maven Central. `help:effective-pom`, `dependency:tree` and `enforcer:enforce` ran successfully. All five enforcer rules pass: `requireJavaVersion [21,22)`, `requireMavenVersion [3.9,)`, `dependencyConvergence`, `banDuplicatePomDependencyVersions`, `bannedDependencies`. Spring Framework resolves to 7.0.10, which meets the ≥ 7.0.10 condition exactly. |
| M1-V2 | **Passed** | `flyway-database-postgresql` resolves to 12.4.0, the same version as `flyway-core`, with no version in the POM; it is managed by the Boot BOM, so no pin was needed. Flyway applied all available migrations on `postgres:17.11` in Testcontainers (`ApplicationContextIT`) and in Docker Compose (`scripts/m1-compose-check.sh --with-probe`, history row `0.1 / V0_1__m1_jsonb_probe.sql / success`). **Caveat:** the only migration at M1 is the test-only probe migration (`src/test/resources/db/m1-probe`). The application has no migrations of its own until V1 in M2. Plain Compose therefore logs "No migrations found" and still creates `flyway_schema_history`. |
| M1-V3 | **Partially passed (API only); final pass condition pending M2** | `spring-boot-starter-security-oauth2-resource-server:4.1.2` resolves. In Spring Security 7.1.2, `NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256)` and `NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256)` encode and decode HS256, and a wrong key is rejected (`JwtHs256ApiTest`). The encoder adds a `kid` header automatically. The pass condition ("auth integration tests pass with HS256 tokens") needs the M2 auth slice and has **not** been run. |
| M1-V4 | **Passed** | Pinned to `json-schema-validator` 3.0.8, the newest 3.0.x on Maven Central. It depends on `tools.jackson.core:jackson-databind` and `jackson-dataformat-yaml`, which converge with the Boot Jackson BOM at 3.1.7 (`dependencyConvergence` passes). The 3.x API (`SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)`, `Schema.validate(JsonNode)`) validates Jackson 3 trees (`JsonSchemaValidatorApiTest`). Timeline schema tests are an M3 deliverable. |
| M1-V5 | **Passed (no fallback needed)** | Hibernate 7.4.13 uses `org.hibernate.type.format.jackson.Jackson3JsonFormatMapper` by default (asserted in `JsonbRoundTripIT`). A `JsonNode` column and a `Map` column round-trip through JSONB: stored as `jsonb` objects and arrays, queryable with `->`/`->>`, read back equal. `hibernate.type.json_format_mapper` is not set. |
| M1-V6 | **Passed** | No `com.fasterxml.jackson.core:jackson-databind` or `jackson-core`, and no `spring-boot-jackson2`, on the compile or test classpath. Enforced by `bannedDependencies` at build time and checked at runtime (`ClasspathGuardTest`). Confirmed that Jackson 3 still uses `com.fasterxml.jackson.core:jackson-annotations` (2.21), which is expected and allowed; `@JsonProperty` from that artifact works with the Jackson 3 `JsonMapper`. |
| M1-V7 | **Passed after a correction to SYSTEM_DESIGN §8.4** | Wiring found: Jackson 3.1 YAML is built on **snakeyaml-engine**, so `YAMLFactory.builder().loadSettings(LoadSettings)` replaces SnakeYAML `LoaderOptions`. `LoadSettings` has no nesting-depth setting, so nesting is limited with `StreamReadConstraints.maxNestingDepth(32)`. Duplicate keys and the 2 MiB code-point limit are rejected as specified. **Two behaviours differ from §8.4:** (1) Jackson does not expand YAML aliases: `*x` becomes the string `"x"`, so `maxAliasesForCollections` has no effect and a document with aliases would parse into wrong data. The parser now **rejects every alias** (including `<<:` merge keys); this is stricter than "≤ 20 aliases". (2) The YAML backend ignores `maxStringLength`; §8.4 sets that limit only for JSON, where it is enforced. §8.4 is updated. Tests: `SafeTreeParsersTest` (aliases, nesting, size, duplicate keys, tags). |
| M1-V8 | **Passed** | Testcontainers 2.0.5. Module coordinates `org.testcontainers:testcontainers-postgresql`, class `org.testcontainers.postgresql.PostgreSQLContainer` (the 1.x `org.testcontainers.containers.PostgreSQLContainer` still exists). `@ServiceConnection` (`org.springframework.boot.testcontainers.service.connection`) wires the datasource. The context loads on `postgres:17.11` (`ApplicationContextIT`). |
| M1-V9 | **Passed** | `spring-boot-starter-webflux-test` provides `WebTestClient` and `@AutoConfigureWebTestClient` (package `org.springframework.boot.webtestclient.autoconfigure`), plus `reactor-test` transitively, so it was not added explicitly. `spring-boot-starter-data-jpa-test` provides `@DataJpaTest` (`org.springframework.boot.data.jpa.test.autoconfigure`) and `@AutoConfigureTestDatabase` (`org.springframework.boot.jdbc.test.autoconfigure`); see `DataJpaSliceIT`. `spring-boot-starter-security-test` provides `SecurityMockServerConfigurers` (`HealthIT`). |
| M1-V10 | **Passed** | Boot 4.1 keeps `spring.reactor.context-propagation` (values `limited` (default) and `auto`); it is set to `auto`. `io.micrometer:context-propagation` is **not** transitive in 4.1.2 and was added explicitly (version from the BOM). A `ThreadLocalAccessor` per MDC key is registered, and `DbExecutor` restores the Reactor context on `db-*` threads, clearing values left by a previous task. `StructuredLoggingAndMdcIT` shows the request's `correlationId` in the MDC on the Netty event loop and on `db-*`, with no leakage across 25 follow-up requests. |
| M1-V11 | **Passed** | PostgreSQL 17.11 with Flyway 12.4.0 and pgjdbc 42.7.14: no "newer than supported" or other WARN-level log line during startup in any test run. Migrations and all integration tests pass. |
| M1-V12 | **Passed** | Default of `spring.datasource.connection-fetch` in Boot 4.1.2 configuration metadata: `eager`. Left at the default. `BlockingBoundaryIT` covers these paths only: a probe endpoint doing JDBC plus a JPA repository call, and the readiness `db` health check. While the guard was armed during those requests, every connection was acquired on a `db-*` thread. A deliberate JDBC call on the Netty event loop was detected as a violation. Startup work (Flyway, Hibernate schema validation) runs unguarded on `main` by design, and no other request paths existed at M1. |
| M1-V13 | **Passed** | Structured console logging: `logging.structured.format.console=ecs` (ECS 8.11 JSON); MDC keys appear as top-level fields (`correlationId`). Actuator health in Boot 4.1 lives in `spring-boot-health` (`org.springframework.boot.health.contributor.*`). `management.endpoint.health.probes.enabled` defaults to `true`. The groups are liveness = `livenessState` and readiness = `readinessState`, `db`, `realtime`. Details are shown to `ADMIN` only. Tests: `HealthIT`, `StructuredLoggingAndMdcIT`, `RealtimeHealthIndicatorTest`; verified under Compose as well. |

## Other findings recorded at M1

- **Boot's `db` health indicator would break ADR-003, so it is replaced.** Boot 4.1.2 runs a blocking
  `HealthIndicator` reactively through `org.springframework.boot.health.contributor.HealthIndicatorAdapter`,
  whose bytecode is `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` (checked with `javap`
  on `spring-boot-health-4.1.2.jar`). The auto-configured `DataSourceHealthIndicator` would therefore run
  JDBC on `boundedElastic`. ADR-003 §2 forbids that: JDBC runs only on the `db-*` executor, whose thread count
  equals the Hikari pool, and "do not use the default `boundedElastic` scheduler for JDBC". The replacement
  bean `dbHealthContributor` (the bean name switches off Boot's auto-configured one) runs its connection check
  on `db-*` and keeps the `db` key in the readiness group. Consequence: when the `db-*` queue is busy, the
  check waits behind real work, may time out (3 s) or be refused, and readiness then reports DOWN.
- **WebSession-backed security context hops threads.** A security chain using the default
  `WebSessionServerSecurityContextRepository` moves request handling to Reactor's `parallel-*` threads
  (the in-memory `WebSession` store uses `subscribeOn(boundedElastic)` + `publishOn(parallel)`). The
  stateless chain uses `NoOpServerSecurityContextRepository`, so handlers stay on the Netty event loop.
- **Thread names.** Boot 4.1 names the Reactor Netty server event-loop threads `webflux-http-epoll-*`
  (not `reactor-http-*`).
- **Docker Hub** returned one `429 Too Many Requests` on an anonymous pull; a retry succeeded.
- **Spring Boot 4.1.x OSS support window** (checked after M1, 2026-10-26):
  - Official evidence. The [Spring support policy](https://spring.io/support-policy/) states that minor
    releases "will be supported for a minimum of 13 months" from release. The
    [Spring Boot 4.1.0 announcement](https://spring.io/blog/2026/06/10/spring-boot-4/) dates 4.1.0 to
    **2026-06-10**. Together: OSS support for 4.1.x lasts **at least until July 2027**.
  - Exact end date **2027-07-31** (commercial support 2028-07-31): reported by secondary sources quoting
    Spring's generations feed (`https://api.spring.io/projects/spring-boot/generations`). That feed, the
    [project support table](https://spring.io/projects/spring-boot#support) (rendered client-side) and
    `calendar.spring.io` could not be read from the verification environment, so the exact day is not
    confirmed first-hand.
  - The design's figure (OSS support until 2027-07) is consistent with the official evidence.
