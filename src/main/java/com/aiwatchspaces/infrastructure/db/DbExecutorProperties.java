package com.aiwatchspaces.infrastructure.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The dedicated blocking database executor (ADR-003 §2).
 *
 * @param threads       number of {@code db-*} threads; must equal {@code spring.datasource.hikari.maximum-pool-size}
 * @param queueCapacity bounded task queue; when full, work is rejected with {@code 503 SERVICE_BUSY}
 */
@ConfigurationProperties("aiws.db-executor")
public record DbExecutorProperties(@DefaultValue("10") int threads, @DefaultValue("500") int queueCapacity) {

    public DbExecutorProperties {
        if (threads < 1) {
            throw new IllegalArgumentException("aiws.db-executor.threads must be >= 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("aiws.db-executor.queue-capacity must be >= 1");
        }
    }
}
