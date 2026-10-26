package com.aiwatchspaces.m1;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** Test-only entity for the M1-V5 JSONB round-trip (table from the test-only probe migration). */
@Entity
@Table(schema = "m1_probe", name = "jsonb_probe")
public class JsonbProbe {

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JsonbProbe() {
    }

    public JsonbProbe(UUID id, JsonNode payload, Map<String, Object> attributes, Instant createdAt) {
        this.id = id;
        this.payload = payload;
        this.attributes = attributes;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
