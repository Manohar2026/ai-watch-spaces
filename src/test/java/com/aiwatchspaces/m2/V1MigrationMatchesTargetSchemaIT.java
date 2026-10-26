package com.aiwatchspaces.m2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.aiwatchspaces.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Flyway V1 produces exactly the V1 slice (app_users, refresh_tokens) of DATABASE_SCHEMA.sql, the
 * approved target state. The slice is executed in a fresh database and both catalogs are compared:
 * tables, columns (type, length, nullability, default), constraints (definition) and indexes.
 */
@IntegrationTest
class V1MigrationMatchesTargetSchemaIT {

    private static final String SLICE_START = "-- Identity and authentication (modules: users, auth)";
    private static final String SLICE_END = "-- Catalogue (module: catalog)";

    private static final String TABLES = """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' ORDER BY table_name""";
    private static final String COLUMNS = """
            SELECT table_name, column_name, data_type, character_maximum_length, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
            ORDER BY table_name, ordinal_position""";
    private static final String CONSTRAINTS = """
            SELECT conrelid::regclass::text AS table_name, conname, contype, pg_get_constraintdef(oid) AS definition
            FROM pg_constraint WHERE connamespace = 'public'::regnamespace
              AND conrelid::regclass::text <> 'flyway_schema_history'
            ORDER BY 1, 2""";
    private static final String INDEXES = """
            SELECT tablename, indexname, indexdef FROM pg_indexes
            WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history' ORDER BY 1, 2""";

    @Autowired
    JdbcTemplate migrated;

    @Autowired
    DataSource dataSource;

    @Test
    void v1MigrationEqualsTheTargetSchemaSlice() throws Exception {
        String schema = Files.readString(Path.of("DATABASE_SCHEMA.sql"));
        String slice = schema.substring(schema.indexOf(SLICE_START), schema.indexOf(SLICE_END));
        assertThat(slice).contains("CREATE TABLE app_users", "CREATE TABLE refresh_tokens");

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        String targetDb = "target_v1_" + System.nanoTime();
        migrated.execute("CREATE DATABASE " + targetDb);
        String targetUrl = hikari.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + targetDb + "$1");
        try (Connection connection = DriverManager.getConnection(targetUrl, hikari.getUsername(), hikari.getPassword())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(slice);
            }
            JdbcTemplate target = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

            assertThat(rows(migrated, TABLES)).containsExactly(Map.of("table_name", "app_users"),
                    Map.of("table_name", "refresh_tokens"));
            assertThat(rows(migrated, TABLES)).isEqualTo(rows(target, TABLES));
            assertThat(rows(migrated, COLUMNS)).isNotEmpty().isEqualTo(rows(target, COLUMNS));
            assertThat(rows(migrated, CONSTRAINTS)).isNotEmpty().isEqualTo(rows(target, CONSTRAINTS));
            assertThat(rows(migrated, INDEXES)).isNotEmpty().isEqualTo(rows(target, INDEXES));
        } finally {
            migrated.execute("DROP DATABASE IF EXISTS " + targetDb);
        }
    }

    private static List<Map<String, Object>> rows(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForList(sql);
    }
}
