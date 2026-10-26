package com.aiwatchspaces.infrastructure.db;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link DbExecutor}. Startup fails unless its thread count equals the Hikari maximum
 * pool size (ADR-003 §2), so no {@code db-*} thread ever parks waiting for a connection that
 * another {@code db-*} thread could not release.
 */
@Configuration(proxyBeanMethods = false)
class DbExecutorConfig {

    @Bean(destroyMethod = "close")
    DbExecutor dbExecutor(DbExecutorProperties properties, DataSource dataSource) throws SQLException {
        int poolSize = hikariMaximumPoolSize(dataSource);
        requireMatchingPoolSize(properties.threads(), poolSize);
        return new DbExecutor(properties.threads(), properties.queueCapacity());
    }

    static int hikariMaximumPoolSize(DataSource dataSource) throws SQLException {
        if (!dataSource.isWrapperFor(HikariDataSource.class)) {
            throw new IllegalStateException("The DataSource must be HikariCP (ADR-003): " + dataSource.getClass().getName());
        }
        return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    }

    static void requireMatchingPoolSize(int executorThreads, int hikariMaximumPoolSize) {
        if (executorThreads != hikariMaximumPoolSize) {
            throw new IllegalStateException("aiws.db-executor.threads (" + executorThreads
                    + ") must equal spring.datasource.hikari.maximum-pool-size (" + hikariMaximumPoolSize + ")");
        }
    }
}
