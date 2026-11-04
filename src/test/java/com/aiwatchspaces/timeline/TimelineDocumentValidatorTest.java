package com.aiwatchspaces.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.timeline.TimelineDocumentValidator.SubtitleTrackReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** R1 timeline cross-reference, uniqueness and range tests for M3. */
class TimelineDocumentValidatorTest {

    private static final UUID TITLE_ID =
            UUID.fromString("0c6b2679-9f94-4bbd-ae4d-47e8d7f1c001");
    private static final UUID OTHER_TITLE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID TRACK_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final int DURATION_MS = 5_000;
    private static final Set<String> CUE_KEYS = Set.of("bloom-caption-01");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final TimelineDocumentValidator validator =
            new TimelineDocumentValidator(new TimelineSchemaValidator());

    @Test
    void acceptsSeedDocumentWithMatchingTitleDurationAndCue() throws IOException {
        assertThatCode(() -> validate(sample(), Map.of())).doesNotThrowAnyException();
    }

    @Test
    void rejectsTitleMismatch() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        document.put("titleId", OTHER_TITLE_ID.toString());

        assertInvalid(document, Map.of(), "TITLE_MISMATCH");
    }

    @Test
    void rejectsDurationMismatch() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        document.put("durationMs", 5_001);

        assertInvalid(document, Map.of(), "DURATION_MISMATCH");
    }

    @Test
    void rejectsDuplicateEventKeys() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ArrayNode events = (ArrayNode) document.path("events");
        ((ObjectNode) events.get(1)).put("eventKey", events.get(0).path("eventKey").asText());

        assertInvalid(document, Map.of(), "DUPLICATE_EVENT_KEY");
    }

    @Test
    void rejectsEventOutsideTitleDuration() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ((ObjectNode) document.path("events").get(0)).put("endMs", 5_001);

        assertInvalid(document, Map.of(), "POSITION_OUT_OF_RANGE");
    }

    @Test
    void rejectsUnknownSubtitleCueKey() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ((ObjectNode) document.path("events").get(3).path("payload")).put("cueKey", "not-a-real-cue");

        assertInvalid(document, Map.of(), "UNKNOWN_CUE_KEY");
    }

    @Test
    void rejectsNonCanonicalLocaleCasing() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ((ObjectNode) document.path("events").get(3).path("options").get(1)).put("locale", "en-in");

        assertInvalid(document, Map.of(), "NON_CANONICAL_LOCALE");
    }

    @Test
    void rejectsSubtitleTrackSwapFromAnotherTitle() throws IOException {
        ObjectNode document = (ObjectNode) sample();
        ObjectNode variation = (ObjectNode) document.path("events").get(3);
        ObjectNode payload = (ObjectNode) variation.path("payload");
        payload.remove("cueKey");
        payload.put("variationType", "SUBTITLE_TRACK_SWAP");

        ArrayNode options = (ArrayNode) variation.path("options");
        options.removeAll();
        options.addObject()
                .put("key", "swap-en")
                .put("label", "English track")
                .put("locale", "en")
                .put("subtitleTrackId", TRACK_ID.toString())
                .put("approved", true)
                .put("default", true)
                .put("sortOrder", 0);
        options.addObject()
                .put("key", "swap-en-in")
                .put("label", "Indian English track")
                .put("locale", "en")
                .put("subtitleTrackId", TRACK_ID.toString())
                .put("approved", true)
                .put("default", false)
                .put("sortOrder", 1);

        Map<UUID, SubtitleTrackReference> tracks = Map.of(
                TRACK_ID, new SubtitleTrackReference(TRACK_ID, OTHER_TITLE_ID, "en", "ACTIVE"));
        assertInvalid(document, tracks, "CROSS_TITLE_TRACK");
    }

    private void validate(JsonNode document, Map<UUID, SubtitleTrackReference> tracks) {
        validator.validate(document, TITLE_ID, DURATION_MS, "en", CUE_KEYS, tracks);
    }

    private void assertInvalid(JsonNode document, Map<UUID, SubtitleTrackReference> tracks, String detailCode) {
        assertThatThrownBy(() -> validate(document, tracks))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiError = (ApiException) error;
                    assertThat(apiError.code()).isEqualTo(ErrorCode.TIMELINE_INVALID);
                    assertThat(apiError.details()).extracting(com.aiwatchspaces.common.error.ErrorDetail::code).contains(detailCode);
                });
    }

    private JsonNode sample() throws IOException {
        try (var input = new ClassPathResource("timeline/samples/the-first-bloom.timeline.json").getInputStream()) {
            return mapper.readTree(input);
        }
    }
}
