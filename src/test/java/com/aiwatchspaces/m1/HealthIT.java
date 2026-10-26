package com.aiwatchspaces.m1;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/** SYSTEM_DESIGN §6.7 / §11, M1-V13: liveness, readiness (db + realtime), details for ADMIN only. */
@IntegrationTest
class HealthIT {

    @Autowired
    WebTestClient client;

    @Autowired
    ApplicationContext context;

    WebTestClient mockAuthClient;

    @BeforeEach
    void setUp() {
        mockAuthClient = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }

    @Test
    void livenessIsPublicAndUp() {
        client.get().uri("/actuator/health/liveness").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    void readinessIsPublicAndHidesComponentsFromAnonymousCallers() {
        client.get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    void readinessGroupContainsDbAndRealtimeWithDetailsForAdmin() {
        mockAuthClient.mutateWith(mockUser("admin").roles("ADMIN"))
                .get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.readinessState.status").isEqualTo("UP")
                .jsonPath("$.components.db.status").isEqualTo("UP")
                .jsonPath("$.components.db.details.database").isEqualTo("PostgreSQL")
                .jsonPath("$.components.realtime.status").isEqualTo("UP")
                .jsonPath("$.components.realtime.details.shuttingDown").isEqualTo(false)
                .jsonPath("$.components.realtime.details.dbExecutorSaturated").isEqualTo(false)
                .jsonPath("$.components.livenessState").doesNotExist();
    }

    @Test
    void nonAdminUserSeesNoDetails() {
        mockAuthClient.mutateWith(mockUser("viewer").roles("USER"))
                .get().uri("/actuator/health/readiness").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.components").doesNotExist();
    }

    @Test
    void metricsRejectAnonymousCallers() {
        // Role-based access with real JWTs (including the database re-check) is covered by AdminAccessIT.
        client.get().uri("/actuator/metrics").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }
}
