package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import reactor.test.StepVerifier;

/**
 * M1-V3 API check: Spring Security 7.1 HS256 encoding ({@code NimbusJwtEncoder.withSecretKey}) and
 * reactive decoding ({@code NimbusReactiveJwtDecoder.withSecretKey}). The full pass condition
 * (auth integration tests with HS256 tokens) belongs to M2.
 */
class JwtHs256ApiTest {

    private static SecretKey randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Test
    void encodesAndDecodesHs256() {
        SecretKey key = randomKey();
        var encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        var decoder = NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ai-watch-spaces-test").audience(java.util.List.of("ai-watch-spaces"))
                .subject("3f2504e0-4f89-11d3-9a0c-0305e82c3301").claim("sys_role", "USER")
                .issuedAt(now).expiresAt(now.plus(15, ChronoUnit.MINUTES)).id("jti-1").build();

        Jwt encoded = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        assertThat(encoded.getHeaders().get("alg")).hasToString("HS256");

        StepVerifier.create(decoder.decode(encoded.getTokenValue()))
                .assertNext(jwt -> {
                    assertThat(jwt.getSubject()).isEqualTo("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
                    assertThat(jwt.getClaimAsString("sys_role")).isEqualTo("USER");
                    assertThat(jwt.getAudience()).containsExactly("ai-watch-spaces");
                })
                .verifyComplete();
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        var encoder = NimbusJwtEncoder.withSecretKey(randomKey()).algorithm(MacAlgorithm.HS256).build();
        var decoder = NimbusReactiveJwtDecoder.withSecretKey(randomKey()).macAlgorithm(MacAlgorithm.HS256).build();
        Instant now = Instant.now();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("x").issuedAt(now).expiresAt(now.plusSeconds(60)).build())).getTokenValue();
        StepVerifier.create(decoder.decode(token)).expectError(JwtException.class).verify();
    }
}
