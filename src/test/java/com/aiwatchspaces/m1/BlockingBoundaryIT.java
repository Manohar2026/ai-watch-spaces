package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiwatchspaces.support.ConnectionThreadGuard;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * ADR-003 §10 blocking-boundary test: once the application is running, every JDBC connection is
 * acquired on a {@code db-*} thread (eager connection fetch, M1-V12). The negative case proves the
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
