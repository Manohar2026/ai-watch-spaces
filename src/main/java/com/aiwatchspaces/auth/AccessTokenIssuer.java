package com.aiwatchspaces.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.security.SystemRole;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Issues HS256 access tokens with the claims of SYSTEM_DESIGN §8.1. Pure CPU work (HMAC). */
@Component
class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    IssuedAccessToken issue(UUID userId, SystemRole role) {
        Instant issuedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(AuthProperties.AUDIENCE))
                .subject(userId.toString())
                .claim(JwtConfig.SYS_ROLE_CLAIM, role.name())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, expiresAt);
    }
}
