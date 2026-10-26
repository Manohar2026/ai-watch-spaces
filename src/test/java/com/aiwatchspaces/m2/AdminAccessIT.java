package com.aiwatchspaces.m2;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Role mapping and the admin database re-check (SYSTEM_DESIGN §8.1, §8.6, Appendix E #4, #8). */
@IntegrationTest
class AdminAccessIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void userTokenIsForbiddenOnAdminEndpoints() {
        var user = auth.newAccount();
        metrics(user.tokens().accessToken()).expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void adminLoginGetsAdminRoleAndAccess() {
        var account = auth.newAccount();
        auth.setSystemRole(account.userId(), "ADMIN");
        var adminTokens = auth.login(account.email(), AuthTestSupport.PASSWORD);
        metrics(adminTokens.accessToken()).expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").headers(h -> h.setBearerAuth(adminTokens.accessToken()))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.components.db.status").isEqualTo("UP");
    }

    @Test
    void adminClaimWithoutAdminInDatabaseIsForbidden() {
        var account = auth.newAccount();
        metrics(auth.forge(account.userId(), "ADMIN", c -> { })).expectStatus().isForbidden();
    }

    @Test
    void demotionTakesEffectImmediatelyDespiteValidAdminToken() {
        var account = auth.newAccount();
        auth.setSystemRole(account.userId(), "ADMIN");
        String adminToken = auth.login(account.email(), AuthTestSupport.PASSWORD).accessToken();
        metrics(adminToken).expectStatus().isOk();
        auth.setSystemRole(account.userId(), "USER");
        metrics(adminToken).expectStatus().isForbidden();
    }

    @Test
    void refreshReReadsTheRoleFromTheDatabase() {
        var account = auth.newAccount();
        metrics(account.tokens().accessToken()).expectStatus().isForbidden();
        auth.setSystemRole(account.userId(), "ADMIN");
        var refreshed = auth.refreshOk(account.tokens().refreshToken());
        metrics(refreshed.accessToken()).expectStatus().isOk();
    }

    @Test
    void healthDetailsRequireAdminInTheDatabase() {
        var account = auth.newAccount();
        auth.setSystemRole(account.userId(), "ADMIN");
        String adminToken = auth.login(account.email(), AuthTestSupport.PASSWORD).accessToken();
        readiness(adminToken).expectBody()
                .jsonPath("$.components.db.status").isEqualTo("UP")
                .jsonPath("$.components.realtime.status").isEqualTo("UP");

        auth.setSystemRole(account.userId(), "USER");
        readiness(adminToken).expectBody().jsonPath("$.status").isEqualTo("UP").jsonPath("$.components").doesNotExist();
        client.get().uri("/actuator/health").headers(h -> h.setBearerAuth(adminToken)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.components").doesNotExist();
    }

    @Test
    void forgedAdminClaimSeesNoHealthDetails() {
        var account = auth.newAccount();
        readiness(auth.forge(account.userId(), "ADMIN", c -> { })).expectBody().jsonPath("$.components").doesNotExist();
        readiness(auth.forge(java.util.UUID.randomUUID(), "ADMIN", c -> { })).expectBody()
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    void demotedAdminIsTreatedAsUserEverywhere() {
        var account = auth.newAccount();
        auth.setSystemRole(account.userId(), "ADMIN");
        String adminToken = auth.login(account.email(), AuthTestSupport.PASSWORD).accessToken();
        auth.setSystemRole(account.userId(), "USER");
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(adminToken)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.systemRole").isEqualTo("USER");
        metrics(adminToken).expectStatus().isForbidden();
    }

    private WebTestClient.ResponseSpec readiness(String token) {
        return client.get().uri("/actuator/health/readiness").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec metrics(String token) {
        return client.get().uri("/actuator/metrics").headers(h -> h.setBearerAuth(token)).exchange();
    }
}
