package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.ConnectionThreadGuard;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * ADR-003 §10 blocking-boundary test: for the request paths exercised here (M1 probe, db health,
 * and the M2 auth flows), every JDBC connection is acquired on a {@code db-*} thread (eager connection fetch, M1-V12). The negative case proves the
 * guard detects an acquisition on a Netty event-loop thread.
 */
@IntegrationTest
class BlockingBoundaryIT {

    /** Boot 4.1 names the Reactor Netty server event-loop threads {@code webflux-http-*}. */
    static final String NETTY_EVENT_LOOP = "webflux-http-";

    @Autowired
    WebTestClient client;

    @Autowired
    ConnectionThreadGuard guard;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    org.springframework.security.oauth2.jwt.JwtEncoder encoder;

    @AfterEach
    void disarm() {
        guard.disarm();
    }

    @Test
    void jdbcJpaAndDbHealthAcquireConnectionsOnlyOnDbThreads() {
        guard.arm();

        client.get().uri("/m1-probe/db").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.select1").isEqualTo(1)
                .jsonPath("$.thread").value(thread -> assertThat((String) thread).startsWith("db-"));

        client.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");

        assertThat(guard.acquiringThreads())
                .as("readiness (db indicator) + probe each acquired at least one connection")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(guard.violations()).isEmpty();
    }

    @Test
    void authFlowsAcquireConnectionsOnlyOnDbThreads() {
        AuthTestSupport auth = new AuthTestSupport(client, jdbc, encoder);
        String email = AuthTestSupport.uniqueEmail();
        java.util.UUID adminId = auth.register(AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD, "Admin");
        auth.setSystemRole(adminId, "ADMIN"); // test-side JDBC, before arming
        guard.arm();

        java.util.UUID userId = auth.register(email, AuthTestSupport.PASSWORD, "Boundary");
        var tokens = auth.login(email, AuthTestSupport.PASSWORD);
        var rotated = auth.refreshOk(tokens.refreshToken());
        auth.refresh(tokens.refreshToken()).expectStatus().isUnauthorized(); // replay path
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(rotated.accessToken())).exchange()
                .expectStatus().isOk();
        client.get().uri("/actuator/metrics").headers(h -> h.setBearerAuth(auth.forge(userId, "ADMIN", c -> { })))
                .exchange().expectStatus().isForbidden(); // admin DB re-check path (not admin in DB)
        client.get().uri("/actuator/health/readiness")
                .headers(h -> h.setBearerAuth(auth.forge(adminId, "ADMIN", c -> { }))).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.components.db.status").isEqualTo("UP");
        client.post().uri("/api/v1/auth/logout").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("refreshToken", rotated.refreshToken())).exchange()
                .expectStatus().isNoContent();

        assertThat(guard.acquiringThreads()).hasSizeGreaterThanOrEqualTo(9);
        assertThat(guard.violations()).isEmpty();
    }

    @Test
    void guardDetectsConnectionAcquiredOnNettyEventLoop() {
        guard.arm();

        client.get().uri("/m1-probe/db-on-event-loop").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.nonBlockingThread").isEqualTo(true)
                .jsonPath("$.thread").value(thread -> assertThat((String) thread).startsWith(NETTY_EVENT_LOOP));

        assertThat(guard.violations()).isNotEmpty().allMatch(name -> name.startsWith(NETTY_EVENT_LOOP));
    }
}
