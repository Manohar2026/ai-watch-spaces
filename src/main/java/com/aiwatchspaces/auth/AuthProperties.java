package com.aiwatchspaces.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings (SYSTEM_DESIGN §8.1–§8.2). The signing key comes from the
 * {@code JWT_SIGNING_KEY} environment variable and is validated by {@link JwtSigningKeys}.
 *
 * @param jwtSigningKey   base64 HS256 key, at least 256 bits
 * @param issuer          value of the {@code iss} claim, required on every token
 * @param accessTokenTtl  default 15 min; anything above 60 min is rejected at startup
 * @param refreshTokenTtl default 14 days
 * @param clockSkew       tolerance for {@code exp}/{@code nbf}; default 30 s
 */
@ConfigurationProperties("aiws.auth")
public record AuthProperties(
        String jwtSigningKey,
        @DefaultValue("ai-watch-spaces") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        @DefaultValue("30s") Duration clockSkew) {

    public static final String AUDIENCE = "ai-watch-spaces";
    public static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofMinutes(60);

    public AuthProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("aiws.auth.issuer must not be blank");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()
                || accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException("aiws.auth.access-token-ttl must be > 0 and <= 60 minutes, was "
                    + accessTokenTtl);
        }
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            throw new IllegalArgumentException("aiws.auth.refresh-token-ttl must be > 0");
        }
        if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("aiws.auth.clock-skew must be between 0 and 5 minutes");
        }
    }

    /** Never prints the signing key. */
    @Override
    public String toString() {
        return "AuthProperties[issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + ", refreshTokenTtl="
                + refreshTokenTtl + ", clockSkew=" + clockSkew + ", jwtSigningKey=<redacted>]";
    }
}
