package com.aiwatchspaces.support;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Applies the application's migrations, then the test-only M1 JSONB probe migration into schema
 * {@code m1_probe} with its own history table. The application's {@code public} schema and
 * {@code flyway_schema_history} therefore contain only real migrations (Appendix E #12). Runs inside
 * Boot's Flyway initializer, before Hibernate validates the schema.
 */
@TestConfiguration(proxyBeanMethods = false)
public class M1ProbeSchemaConfiguration {

    public static final String PROBE_SCHEMA = "m1_probe";
    public static final String PROBE_HISTORY_TABLE = "flyway_m1_probe_history";

    @Bean
    FlywayMigrationStrategy migrateApplicationThenM1Probe() {
        return flyway -> {
            flyway.migrate();
            Flyway.configure()
                    .dataSource(flyway.getConfiguration().getDataSource())
                    .locations("classpath:db/m1-probe")
                    .schemas(PROBE_SCHEMA)
                    .table(PROBE_HISTORY_TABLE)
                    .load()
                    .migrate();
        };
    }
}
