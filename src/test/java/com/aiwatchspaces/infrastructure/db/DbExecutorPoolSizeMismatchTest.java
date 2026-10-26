package com.aiwatchspaces.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ADR-003 §2: startup fails when the db executor and the Hikari pool differ in size. */
class DbExecutorPoolSizeMismatchTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DbExecutorProperties.class)
    static class PoolConfig {
        @Bean
        HikariDataSource dataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setMaximumPoolSize(10);
            return dataSource; // never started: no connection is opened
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PoolConfig.class, DbExecutorConfig.class);

    @Test
    void matchingSizesStart() {
        runner.withPropertyValues("aiws.db-executor.threads=10")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DbExecutor.class));
    }

    @Test
    void mismatchedSizesFailStartup() {
        runner.withPropertyValues("aiws.db-executor.threads=4")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("must equal spring.datasource.hikari.maximum-pool-size (10)"));
    }
}
