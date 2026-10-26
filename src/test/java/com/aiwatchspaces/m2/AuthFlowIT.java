package com.aiwatchspaces.m2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.AuthTestSupport.Tokens;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Register, login, /me and validation (SYSTEM_DESIGN §6.2, §8.3, §8.5). */
@IntegrationTest
class AuthFlowIT {

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
    void registerLoginAndReadProfile() {
        String email = "Mixed.Case-" + UUID.randomUUID() + "@Example.TEST";
        UUID userId = auth.register(email, AuthTestSupport.PASSWORD, "  Asha  ");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT email, normalized_email, password_hash, display_name, system_role, subtitle_locale FROM app_users WHERE id = ?",
                userId);
        assertThat(row.get("email")).isEqualTo(email);
        assertThat(row.get("normalized_email")).isEqualTo(email.toLowerCase());
        assertThat((String) row.get("password_hash")).startsWith("{bcrypt}$2a$12$");
        assertThat(row).containsEntry("display_name", "Asha").containsEntry("system_role", "USER")
                .containsEntry("subtitle_locale", "en");

        Instant before = Instant.now();
        Tokens tokens = auth.login(email.toUpperCase(), AuthTestSupport.PASSWORD);
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).hasSize(43);
        assertThat(Duration.between(before, tokens.accessTokenExpiresAt()))
                .isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15).plusSeconds(5));
        assertThat(Duration.between(before, tokens.refreshTokenExpiresAt()))
                .isBetween(Duration.ofDays(14).minusMinutes(1), Duration.ofDays(14).plusMinutes(1));
        assertThat(tokens.user()).containsEntry("id", userId.toString()).containsEntry("systemRole", "USER");

        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(tokens.accessToken())).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(userId.toString())
                .jsonPath("$.displayName").isEqualTo("Asha")
                .jsonPath("$.systemRole").isEqualTo("USER")
                .jsonPath("$.passwordHash").doesNotExist();
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() {
        String email = AuthTestSupport.uniqueEmail();
        auth.register(email, AuthTestSupport.PASSWORD, "First");
        register(email.toUpperCase(), AuthTestSupport.PASSWORD, "Second")
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void registrationValidation() {
        register(" " + AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD, "Name").expectStatus().isBadRequest();
        register("not-an-email", AuthTestSupport.PASSWORD, "Name").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.details[0].field").isEqualTo("email");
        register(AuthTestSupport.uniqueEmail(), "short-pass", "Name").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details[0].code").isEqualTo("PASSWORD_POLICY");
        // 40 characters but 80 UTF-8 bytes: over BCrypt's 72-byte limit.
        register(AuthTestSupport.uniqueEmail(), "é".repeat(40), "Name").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details[0].code").isEqualTo("PASSWORD_POLICY");
        register(AuthTestSupport.uniqueEmail(), "x".repeat(129), "Name").expectStatus().isBadRequest();
        register(AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD, "<b>bold</b>").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details[0].code").isEqualTo("MARKUP_NOT_ALLOWED");
        register(AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD, "   ").expectStatus().isBadRequest();
        register(AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD, "a".repeat(81)).expectStatus().isBadRequest();
        // a < b is plain text, not markup.
        register(AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD, "a < b").expectStatus().isCreated();
        client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", AuthTestSupport.uniqueEmail(), "password", AuthTestSupport.PASSWORD,
                        "displayName", "Name", "userId", UUID.randomUUID().toString()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void loginFailuresAreGeneric() {
        String email = AuthTestSupport.uniqueEmail();
        auth.register(email, AuthTestSupport.PASSWORD, "Name");
        String wrongPassword = login(email, "wrong password!!").expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
        String unknownUser = login(AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD).expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
        String tooLong = login(email, "é".repeat(40)).expectStatus().isUnauthorized()
                .expectBody(String.class).returnResult().getResponseBody();
        for (String body : new String[] {wrongPassword, unknownUser, tooLong}) {
            assertThat(body).contains("\"code\":\"INVALID_CREDENTIALS\"", "\"message\":\"Invalid email or password.\"");
        }
    }

    @Test
    void profileUpdate() {
        var account = auth.newAccount();
        String token = account.tokens().accessToken();
        client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", " New Name ", "subtitleLocale", "en-in")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("New Name")
                .jsonPath("$.subtitleLocale").isEqualTo("en-IN");
        client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("subtitleLocale", "not a locale!")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details[0].code").isEqualTo("INVALID_LOCALE");
        client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of()).exchange()
                .expectStatus().isBadRequest();
        client.patch().uri("/api/v1/me").headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("systemRole", "ADMIN")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT system_role FROM app_users WHERE id = ?", String.class, account.userId()))
                .isEqualTo("USER");
    }

    private WebTestClient.ResponseSpec register(String email, String password, String displayName) {
        return client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password, "displayName", displayName)).exchange();
    }

    private WebTestClient.ResponseSpec login(String email, String password) {
        return client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password)).exchange();
    }
}
