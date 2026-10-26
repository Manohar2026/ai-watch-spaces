package com.aiwatchspaces.infrastructure.health;

import java.time.Duration;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;

/**
 * Replaces Boot's blocking {@code db} health indicator (which WebFlux would run on the shared
 * {@code boundedElastic} scheduler) with one that runs its JDBC check on the {@code db-*} executor
 * (ADR-003 §1–§2). The bean name suppresses Boot's {@code dbHealthContributor} auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
class DbHealthConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Bean(name = "dbHealthContributor")
    ReactiveHealthIndicator dbHealthContributor(DbExecutor dbExecutor, JdbcTemplate jdbcTemplate) {
        return () -> dbExecutor.call(() -> jdbcTemplate.execute((ConnectionCallback<Health>) connection -> {
                    boolean valid = connection.isValid((int) TIMEOUT.toSeconds());
                    Health.Builder builder = valid ? Health.up() : Health.down();
                    return builder.withDetail("database", connection.getMetaData().getDatabaseProductName())
                            .withDetail("version", connection.getMetaData().getDatabaseProductVersion())
                            .build();
                }))
                .timeout(TIMEOUT)
                .onErrorResume(e -> Mono.just(
                        Health.down().withDetail("error", e.getClass().getSimpleName()).build()));
    }
}
