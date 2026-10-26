package com.aiwatchspaces.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Helpers for M2 tests: register/login through the real API, forge tokens, change roles in the DB. */
public final class AuthTestSupport {

    public static final String PASSWORD = "correct horse battery staple";
    public static final String TEST_ISSUER = "ai-watch-spaces-test";

    private final WebTestClient client;
    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;

    public AuthTestSupport(WebTestClient client, JdbcTemplate jdbc, JwtEncoder encoder) {
        this.client = client;
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tokens(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                         Instant refreshTokenExpiresAt, Map<String, Object> user) {
    }

    public record Account(UUID userId, String email, Tokens tokens) {
    }

    public static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }

    public UUID register(String email, String password, String displayName) {
        Map<?, ?> body = client.post().uri("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password, "displayName", displayName))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString((String) body.get("userId"));
    }

    public Tokens login(String email, String password) {
        return client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange().expectStatus().isOk()
                .expectBody(Tokens.class).returnResult().getResponseBody();
    }

    public Account newAccount() {
        String email = uniqueEmail();
        UUID userId = register(email, PASSWORD, "Test User");
        return new Account(userId, email, login(email, PASSWORD));
    }

    public WebTestClient.ResponseSpec refresh(String refreshToken) {
        return client.post().uri("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken)).exchange();
    }

    public Tokens refreshOk(String refreshToken) {
        return refresh(refreshToken).expectStatus().isOk().expectBody(Tokens.class).returnResult().getResponseBody();
    }

    public void setSystemRole(UUID userId, String role) {
        assertThat(jdbc.update("UPDATE app_users SET system_role = ? WHERE id = ?", role, userId)).isEqualTo(1);
    }

    public List<Map<String, Object>> familyRows(UUID userId) {
        return jdbc.queryForList("""
                SELECT id, family_id, revoked_reason, revoked_at, replaced_by_token_id
                FROM refresh_tokens WHERE user_id = ? ORDER BY created_at, id""", userId);
    }

    /** A token signed with the application's test key; {@code customizer} may break any claim. */
    public String forge(UUID subject, String role, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(TEST_ISSUER)
                .audience(List.of("ai-watch-spaces"))
                .subject(subject.toString())
                .claim("sys_role", role)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString());
        customizer.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims.build())).getTokenValue();
    }
}
