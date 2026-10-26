package com.aiwatchspaces.m2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** SYSTEM_DESIGN §8.7 auth limits, with the production defaults (own context). */
@IntegrationTest
@TestPropertySource(properties = {
        "aiws.rate-limits.auth.login-per-email.limit=5", "aiws.rate-limits.auth.login-per-email.window=1m",
        "aiws.rate-limits.auth.login-per-ip.limit=20", "aiws.rate-limits.auth.login-per-ip.window=1m",
        "aiws.rate-limits.auth.register-per-ip.limit=5", "aiws.rate-limits.auth.register-per-ip.window=1h"})
class RateLimitIT {

    @Autowired
    WebTestClient client;

    @Test
    void loginIsLimitedPerEmailThenRegisterPerIp() {
        String email = AuthTestSupport.uniqueEmail();
        for (int i = 0; i < 5; i++) {
            login(email).expectStatus().isUnauthorized();
        }
        String retryAfter = login(email).expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")
                .returnResult().getResponseHeaders().getFirst("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);
        // Another email from the same IP is still allowed (per-IP limit is 20/min).
        login(AuthTestSupport.uniqueEmail()).expectStatus().isUnauthorized();

        for (int i = 0; i < 5; i++) {
            register().expectStatus().isCreated();
        }
        String registerRetry = register().expectStatus().isEqualTo(429)
                .returnResult(String.class).getResponseHeaders().getFirst("Retry-After");
        assertThat(Long.parseLong(registerRetry)).isBetween(1L, 3600L);
    }

    private WebTestClient.ResponseSpec login(String email) {
        return client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "wrong password!!")).exchange();
    }

    private WebTestClient.ResponseSpec register() {
        return client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", AuthTestSupport.uniqueEmail(), "password", AuthTestSupport.PASSWORD,
                        "displayName", "Rate Limited")).exchange();
    }
}
