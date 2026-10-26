package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.support.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** M1-V5: JSONB round-trip through Hibernate 7 with Jackson 3 (no Jackson 2 on the classpath). */
@IntegrationTest
class JsonbRoundTripIT {

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    JsonbProbeRepository repository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DbExecutor dbExecutor;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void hibernateUsesTheJackson3JsonFormatMapper() {
        var options = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();
        assertThat(options.getJsonFormatMapper()).isInstanceOf(Jackson3JsonFormatMapper.class);
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void jsonbRoundTripsThroughHibernate() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-26T10:15:30.123456789Z").truncatedTo(ChronoUnit.MICROS);
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("text", "Plain text, unicode: café – 日本語 – 🎬, quote \" and <b>");
        payload.put("positionMs", 123_456);
        payload.put("ratio", 0.75);
        payload.put("flag", true);
        payload.putNull("nothing");
        payload.putArray("sourceIds").add("a").add("b");
        payload.putObject("nested").put("depth", 2).putObject("inner").put("k", "v");
        Map<String, Object> attributes = Map.of("locale", "en-IN", "count", 3, "tags", List.of("x", "y"));

        JsonbProbe saved = dbExecutor.call(() -> transactionTemplate.execute(status ->
                repository.saveAndFlush(new JsonbProbe(id, payload, attributes, createdAt)))).block();
        assertThat(saved).isNotNull();

        JsonbProbe loaded = dbExecutor.call(() -> transactionTemplate.execute(status ->
                repository.findById(id).orElseThrow())).block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getPayload()).isEqualTo(payload);
        assertThat(loaded.getAttributes()).isEqualTo(attributes);
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);

        // Stored as real JSONB (an object, queryable with JSON operators), not as a JSON string.
        Map<String, Object> raw = jdbcTemplate.queryForMap("""
                SELECT jsonb_typeof(payload) AS payload_type,
                       payload -> 'nested' -> 'inner' ->> 'k' AS inner_k,
                       (payload ->> 'positionMs')::int AS position_ms,
                       jsonb_typeof(attributes -> 'tags') AS tags_type,
                       pg_typeof(payload)::text AS column_type
                FROM m1_probe.jsonb_probe WHERE id = ?""", id);
        assertThat(raw).containsEntry("payload_type", "object")
                .containsEntry("inner_k", "v")
                .containsEntry("position_ms", 123_456)
                .containsEntry("tags_type", "array")
                .containsEntry("column_type", "jsonb");

        JsonNode fromDatabaseText = jsonMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload::text FROM m1_probe.jsonb_probe WHERE id = ?", String.class, id));
        assertThat(fromDatabaseText).isEqualTo(payload);
    }
}
