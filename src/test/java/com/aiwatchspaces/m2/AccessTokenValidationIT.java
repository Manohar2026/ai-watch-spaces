package com.aiwatchspaces.m2;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * M1-V3 pass condition and SYSTEM_DESIGN §8.1 / Appendix E #3–#4: HS256 bearer tokens through the
 * real security chain, with issuer, audience, timestamp (30 s skew), type and claim validation.
 */
@IntegrationTest
class AccessTokenValidationIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    AuthTestSupport auth;
    UUID userId;

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(client, jdbc, encoder);
        userId = auth.newAccount().userId();
    }

    @Test
    void issuedAndForgedValidTokensAreAccepted() {
        var account = auth.newAccount();
        me(account.tokens().accessToken()).expectStatus().isOk();
        me(auth.forge(account.userId(), "USER", c -> { })).expectStatus().isOk();
    }

    @Test
    void missingTokenIsUnauthenticated() {
        client.get().uri("/api/v1/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void expiredTokenIsTokenExpired() {
        Instant now = Instant.now();
        String expired = auth.forge(userId, "USER", c -> c.issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(60)));
        unauthorized(expired, "TOKEN_EXPIRED");
    }

    @Test
    void tokenExpiredWithinClockSkewIsAccepted() {
        Instant now = Instant.now();
        me(auth.forge(userId, "USER", c -> c.issuedAt(now.minusSeconds(900)).expiresAt(now.minusSeconds(10))))
                .expectStatus().isOk();
    }

    @Test
    void notYetValidTokenIsRejected() {
        unauthorized(auth.forge(userId, "USER", c -> c.notBefore(Instant.now().plusSeconds(120))), "UNAUTHENTICATED");
    }

    @Test
    void wrongIssuerIsRejected() {
        unauthorized(auth.forge(userId, "USER", c -> c.issuer("someone-else")), "UNAUTHENTICATED");
    }

    @Test
    void wrongAudienceIsRejected() {
        unauthorized(auth.forge(userId, "USER", c -> c.audience(List.of("another-api"))), "UNAUTHENTICATED");
    }

    @Test
    void missingOrMalformedClaimsAreRejected() {
        unauthorized(auth.forge(userId, "USER", c -> c.claims(claims -> claims.remove("jti"))), "UNAUTHENTICATED");
        unauthorized(auth.forge(userId, "USER", c -> c.claims(claims -> claims.remove("iat"))), "UNAUTHENTICATED");
        unauthorized(auth.forge(userId, "SUPERUSER", c -> { }), "UNAUTHENTICATED");
        unauthorized(auth.forge(userId, "USER", c -> c.claims(claims -> claims.remove("sys_role"))), "UNAUTHENTICATED");
        unauthorized(auth.forge(userId, "USER", c -> c.subject("not-a-uuid")), "UNAUTHENTICATED");
        unauthorized(auth.forge(userId, "USER", c -> c.claims(claims -> claims.remove("exp"))), "UNAUTHENTICATED");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        byte[] otherKey = "another-key-with-at-least-32-bytes!!".getBytes();
        JwtEncoder other = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherKey, "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256).build();
        Instant now = Instant.now();
        String token = other.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder().issuer(AuthTestSupport.TEST_ISSUER).audience(List.of("ai-watch-spaces"))
                        .subject(userId.toString()).claim("sys_role", "USER").issuedAt(now)
                        .expiresAt(now.plusSeconds(300)).id("x").build())).getTokenValue();
        unauthorized(token, "UNAUTHENTICATED");
    }

    @Test
    void tamperedUnsignedAndGarbageTokensAreRejected() {
        String valid = auth.forge(userId, "USER", c -> { });
        String[] parts = valid.split("\\.");
        String elevated = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"USER\"", "\"ADMIN\"").getBytes());
        unauthorized(parts[0] + "." + elevated + "." + parts[2], "UNAUTHENTICATED");
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        unauthorized(noneHeader + "." + parts[1] + ".", "UNAUTHENTICATED");
        unauthorized("not.a.jwt", "UNAUTHENTICATED");
    }

    @Test
    void wrongTypeHeaderIsRejected() {
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("at+jwt").build(),
                JwtClaimsSet.builder().issuer(AuthTestSupport.TEST_ISSUER).audience(List.of("ai-watch-spaces"))
                        .subject(userId.toString()).claim("sys_role", "USER").issuedAt(now)
                        .expiresAt(now.plusSeconds(300)).id("x").build())).getTokenValue();
        unauthorized(token, "UNAUTHENTICATED");
    }

    private WebTestClient.ResponseSpec me(String token) {
        return client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(token)).exchange();
    }

    private void unauthorized(String token, String code) {
        me(token).expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo(code);
    }
}
