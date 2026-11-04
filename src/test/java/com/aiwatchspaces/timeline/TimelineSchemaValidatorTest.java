package com.aiwatchspaces.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Schema v1 acceptance and rejection tests (SYSTEM_DESIGN §5.4, M3). */
class TimelineSchemaValidatorTest {

    private final TimelineSchemaValidator validator = new TimelineSchemaValidator();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void sampleTimelineMatchesSchemaV1() throws IOException {
        JsonNode sample = sample();
        assertThatCode(() -> validator.validate(sample)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownProperties() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        document.put("unexpected", "not allowed");

        assertThatThrownBy(() -> validator.validate(document))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.TIMELINE_INVALID));
    }

    @Test
    void rejectsOptionsOnARegularSceneEvent() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ((ObjectNode) document.path("events").get(0)).putArray("options");

        assertThatThrownBy(() -> validator.validate(document))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.TIMELINE_INVALID));
    }

    @Test
    void rejectsVariationPointWithoutTwoApprovedOptions() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ((ObjectNode) document.path("events").get(3).path("options").get(1)).put("approved", false);

        assertThatThrownBy(() -> validator.validate(document))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.TIMELINE_INVALID));
    }

    @Test
    void rejectsVariationPointWithoutExactlyOneApprovedDefault() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ((ObjectNode) document.path("events").get(3).path("options").get(1)).put("default", true);

        assertThatThrownBy(() -> validator.validate(document))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.TIMELINE_INVALID));
    }

    private JsonNode sample() throws IOException {
        try (var input = new ClassPathResource("timeline/samples/the-first-bloom.timeline.json").getInputStream()) {
            return mapper.readTree(input);
        }
    }
}
