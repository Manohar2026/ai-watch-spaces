package com.aiwatchspaces.timeline;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.List;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Loads the checked-in Draft 2020-12 timeline schema and validates Jackson 3 trees (M1-V4). */
@Component
class TimelineSchemaValidator {

    private final Schema schema;

    TimelineSchemaValidator() {
        JsonMapper mapper = JsonMapper.builder().build();
        try (InputStream input = new ClassPathResource("timeline/schema-v1.json").getInputStream()) {
            JsonNode schemaNode = mapper.readTree(input);
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load timeline schema v1.", e);
        }
    }

    void validate(JsonNode document) {
        List<com.networknt.schema.Error> errors = schema.validate(document);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.TIMELINE_INVALID, "Timeline metadata is invalid.",
                    List.of(new ErrorDetail("document", "JSON_SCHEMA", errors.toString())));
        }
    }
}
