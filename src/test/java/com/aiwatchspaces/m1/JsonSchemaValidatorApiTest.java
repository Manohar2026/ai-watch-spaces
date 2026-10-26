package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * M1-V4 API check: json-schema-validator 3.0.x validates Jackson 3 trees. Timeline schema tests
 * are an M3 deliverable.
 */
class JsonSchemaValidatorApiTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void validatesJackson3TreesWithDraft202012() {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(mapper.readTree("""
                {"type":"object","required":["schemaVersion"],"additionalProperties":false,
                 "properties":{"schemaVersion":{"const":1},"startMs":{"type":"integer","minimum":0}}}"""));

        assertThat(schema.validate(mapper.readTree("{\"schemaVersion\":1,\"startMs\":0}"))).isEmpty();
        assertThat(schema.validate(mapper.readTree("{\"schemaVersion\":2,\"startMs\":-1,\"x\":true}")))
                .hasSizeGreaterThanOrEqualTo(3);
    }
}
