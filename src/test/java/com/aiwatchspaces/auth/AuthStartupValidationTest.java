package com.aiwatchspaces.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** SYSTEM_DESIGN §8.1: startup fails on a missing/short key or an access TTL above 60 minutes. */
class AuthStartupValidationTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class, JwtConfig.class);

    @Test
    void startsWithA256BitKeyAndDefaults() {
        runner.withPropertyValues("aiws.auth.jwt-signing-key=" + VALID_KEY).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ReactiveJwtDecoder.class);
            AuthProperties properties = context.getBean(AuthProperties.class);
            assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
            assertThat(properties.clockSkew()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.toString()).doesNotContain(VALID_KEY);
        });
    }

    @Test
    void missingKeyFailsStartup() {
        runner.run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("JWT_SIGNING_KEY is missing"));
    }

    @Test
    void shortKeyFailsStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
        runner.withPropertyValues("aiws.auth.jwt-signing-key=" + shortKey).run(context -> assertThat(context)
                .hasFailed().getFailure().rootCause().hasMessageContaining("at least 32 bytes (256 bits), was 31")
                .message().doesNotContain(shortKey));
    }

    @Test
    void placeholderKeyFailsStartup() {
        runner.withPropertyValues("aiws.auth.jwt-signing-key=change-me").run(context -> assertThat(context)
                .hasFailed().getFailure().rootCause().hasMessageContaining("not valid base64"));
    }

    @Test
    void accessTokenTtlAbove60MinutesFailsStartup() {
        runner.withPropertyValues("aiws.auth.jwt-signing-key=" + VALID_KEY, "aiws.auth.access-token-ttl=61m")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("must be > 0 and <= 60 minutes"));
        runner.withPropertyValues("aiws.auth.jwt-signing-key=" + VALID_KEY, "aiws.auth.access-token-ttl=60m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void propertiesRejectInvalidValuesDirectly() {
        assertThatThrownBy(() -> new AuthProperties(VALID_KEY, "iss", Duration.ZERO, Duration.ofDays(14),
                Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties(VALID_KEY, " ", Duration.ofMinutes(15), Duration.ofDays(14),
                Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }
}
