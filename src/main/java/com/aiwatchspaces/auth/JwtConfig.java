package com.aiwatchspaces.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.aiwatchspaces.common.security.SystemRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * HS256 access-token encoding and validation (SYSTEM_DESIGN §8.1, Appendix E #3–#4). Validation:
 * HS256 only, {@code typ} JWT, issuer, audience, {@code exp}/{@code nbf} with clock skew, and the
 * presence/shape of {@code sub}, {@code sys_role}, {@code iat}, {@code jti}.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    /** OAuth2 error code used to tell an expired token apart from other invalid tokens. */
    static final String TOKEN_EXPIRED_ERROR = "token_expired";

    static final String SYS_ROLE_CLAIM = "sys_role";

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        return JwtSigningKeys.decode(properties.jwtSigningKey());
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthProperties properties, Clock clock) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setClaimSetConverter(JwtConfig::convertClaims);
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    private static final MappedJwtClaimSetConverter DEFAULT_CLAIMS = MappedJwtClaimSetConverter.withDefaults(Map.of());

    /**
     * Spring's default claim conversion fills in a missing {@code iat} with the current time. Keep it
     * missing so the required-claims check can reject tokens without {@code iat} (found at M2).
     */
    static Map<String, Object> convertClaims(Map<String, Object> rawClaims) {
        Map<String, Object> converted = new HashMap<>(DEFAULT_CLAIMS.convert(rawClaims));
        if (!rawClaims.containsKey(JwtClaimNames.IAT)) {
            converted.remove(JwtClaimNames.IAT);
        }
        return converted;
    }

    static OAuth2TokenValidator<Jwt> validator(AuthProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new ExpiryClassifier(properties, clock));
        validators.add(timestamps);
        validators.add(JwtTypeValidator.jwt());
        validators.add(new JwtIssuerValidator(properties.issuer()));
        validators.add(new JwtAudienceValidator(AuthProperties.AUDIENCE));
        validators.add(JwtConfig::requiredClaims);
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static OAuth2TokenValidatorResult requiredClaims(Jwt jwt) {
        List<OAuth2Error> errors = new ArrayList<>();
        if (!isUuid(jwt.getSubject())) {
            errors.add(invalid("sub must be a user id"));
        }
        if (!isSystemRole(jwt.getClaimAsString(SYS_ROLE_CLAIM))) {
            errors.add(invalid("sys_role must be USER or ADMIN"));
        }
        if (jwt.getIssuedAt() == null) {
            errors.add(invalid("iat is required"));
        }
        if (jwt.getId() == null || jwt.getId().isBlank()) {
            errors.add(invalid("jti is required"));
        }
        return errors.isEmpty() ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(errors);
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isSystemRole(String value) {
        if (value == null) {
            return false;
        }
        for (SystemRole role : SystemRole.values()) {
            if (role.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static OAuth2Error invalid(String description) {
        return new OAuth2Error("invalid_token", description, null);
    }

    /** Adds a distinguishable error when {@code exp} has passed beyond the allowed skew. */
    private record ExpiryClassifier(AuthProperties properties, Clock clock) implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt != null && Instant.now(clock).minus(properties.clockSkew()).isAfter(expiresAt)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(TOKEN_EXPIRED_ERROR, "Token expired", null));
            }
            return OAuth2TokenValidatorResult.success();
        }
    }
}
