package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.support.IntegrationTest;
import com.aiwatchspaces.support.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** M1 exit criterion: context loads on Testcontainers PostgreSQL 17 (M1-V2, M1-V8, M1-V11). */
@IntegrationTest
class ApplicationContextIT {

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource dataSource;

    @Autowired
    DbExecutor dbExecutor;

    @Test
    void runsOnNettyWithWebFluxAndNoSpringMvc() {
        assertThat(context).isInstanceOf(ReactiveWebServerApplicationContext.class);
        String server = ((ReactiveWebServerApplicationContext) context).getWebServer().getClass().getName();
        assertThat(server).contains("Netty");
        assertThatThrownBy(() -> Class.forName("org.springframework.web.servlet.DispatcherServlet"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void connectsToPostgres17FromTheTestcontainersImage() {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        assertThat(TestcontainersConfiguration.POSTGRES_IMAGE).isEqualTo("postgres:17.11");
        assertThat(version).startsWith("17.11");
    }

    @Test
    void flywayWithPostgresModuleAppliedAllApplicationMigrations() {
        MigrationInfo[] all = flyway.info().all();
        assertThat(all).isNotEmpty();
        assertThat(Arrays.stream(all).map(MigrationInfo::getState)).allMatch(state -> state == MigrationState.SUCCESS);
        assertThat(Arrays.stream(all).map(MigrationInfo::getScript)).containsExactly("V1__auth_users.sql");
        Integer historyRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE success", Integer.class);
        assertThat(historyRows).isEqualTo(all.length);
    }

    @Test
    void m1ProbeMigrationIsKeptOutOfTheApplicationSchema() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT script FROM public.flyway_schema_history", String.class)).doesNotContain("V0_1__m1_jsonb_probe.sql");
        assertThat(jdbcTemplate.queryForList(
                "SELECT script FROM m1_probe.flyway_m1_probe_history WHERE success AND type = 'SQL'", String.class))
                .containsExactly("V0_1__m1_jsonb_probe.sql");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE '%probe%'",
                Integer.class)).isZero();
    }

    @Test
    void dbExecutorThreadCountEqualsHikariMaximumPoolSize() throws Exception {
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        assertThat(dbExecutor.threadCount()).isEqualTo(poolSize).isEqualTo(10);
        assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(2000);
    }
}
