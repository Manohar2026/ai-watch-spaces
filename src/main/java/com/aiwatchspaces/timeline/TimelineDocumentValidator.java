package com.aiwatchspaces.timeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.aiwatchspaces.common.text.LocaleTags;
import com.aiwatchspaces.common.text.PlainText;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Cross-reference and range rules R1/R11 that JSON Schema cannot express by itself.
 * Timeline positions are integer milliseconds throughout this validator.
 */
@Component
class TimelineDocumentValidator {

    private final TimelineSchemaValidator schema;

    TimelineDocumentValidator(TimelineSchemaValidator schema) {
        this.schema = schema;
    }

    void validate(JsonNode document, UUID expectedTitleId, int expectedDurationMs,
                  String defaultLocale, Set<String> defaultLocaleCueKeys,
                  Map<UUID, SubtitleTrackReference> subtitleTracks) {
        schema.validate(document);
        List<ErrorDetail> errors = new ArrayList<>();

        String documentTitle = document.path("titleId").asText();
        if (!expectedTitleId.toString().equals(documentTitle)) {
            add(errors, "titleId", "TITLE_MISMATCH", "must match the title in the request");
        }
        if (document.path("durationMs").asInt(-1) != expectedDurationMs) {
            add(errors, "durationMs", "DURATION_MISMATCH", "must equal the title durationMs");
        }

        Set<String> eventKeys = new HashSet<>();
        JsonNode events = document.path("events");
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            String path = "events[" + index + "]";
            String eventKey = event.path("eventKey").asText();
            if (!eventKeys.add(eventKey)) {
                add(errors, path + ".eventKey", "DUPLICATE_EVENT_KEY", "eventKey must be unique in a timeline version");
            }

            int startMs = event.path("startMs").asInt(-1);
            if (startMs < 0 || startMs > expectedDurationMs) {
                add(errors, path + ".startMs", "POSITION_OUT_OF_RANGE", "must be within the title duration");
            }
            JsonNode endNode = event.get("endMs");
            if (endNode != null && !endNode.isNull()) {
                int endMs = endNode.asInt(-1);
                if (endMs < startMs || endMs > expectedDurationMs) {
                    add(errors, path + ".endMs", "POSITION_OUT_OF_RANGE",
                            "must be greater than or equal to startMs and within the title duration");
                }
            }

            validatePlainText(event.path("payload"), path + ".payload", errors);
            if ("VARIATION_POINT".equals(event.path("type").asText())) {
                validateVariationPoint(event, path, expectedTitleId, defaultLocale,
                        defaultLocaleCueKeys, subtitleTracks, errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.TIMELINE_INVALID, "Timeline metadata is invalid.", errors);
        }
    }

    private static void validateVariationPoint(JsonNode event, String path, UUID titleId,
                                               String defaultLocale, Set<String> cueKeys,
                                               Map<UUID, SubtitleTrackReference> tracks,
                                               List<ErrorDetail> errors) {
        JsonNode payload = event.path("payload");
        String variationType = payload.path("variationType").asText();
        if ("SUBTITLE_TEXT".equals(variationType)) {
            String cueKey = payload.path("cueKey").asText();
            if (!cueKeys.contains(cueKey)) {
                add(errors, path + ".payload.cueKey", "UNKNOWN_CUE_KEY",
                        "must exist in the ACTIVE track for the title default locale (" + defaultLocale + ")");
            }
        }
        JsonNode options = event.path("options");
        int approved = 0;
        int defaults = 0;
        for (int i = 0; i < options.size(); i++) {
            JsonNode option = options.get(i);
            String optionPath = path + ".options[" + i + "]";
            if (option.path("approved").asBoolean(false)) {
                approved++;
            }
            if (option.path("default").asBoolean(false)) {
                defaults++;
                if (!option.path("approved").asBoolean(false)) {
                    add(errors, optionPath + ".default", "DEFAULT_NOT_APPROVED",
                            "the default option must be approved");
                }
            }
            try {
                LocaleTags.canonicalize(optionPath + ".locale", option.path("locale").asText());
            } catch (ApiException e) {
                add(errors, optionPath + ".locale", "INVALID_LOCALE", "must be a valid BCP 47 language tag");
            }

            if ("SUBTITLE_TRACK_SWAP".equals(variationType)) {
                UUID trackId;
                try {
                    trackId = UUID.fromString(option.path("subtitleTrackId").asText());
                } catch (IllegalArgumentException e) {
                    add(errors, optionPath + ".subtitleTrackId", "INVALID_TRACK", "must identify a subtitle track");
                    continue;
                }
                SubtitleTrackReference track = tracks.get(trackId);
                if (track == null || !track.titleId().equals(titleId)) {
                    add(errors, optionPath + ".subtitleTrackId", "CROSS_TITLE_TRACK",
                            "subtitle track must belong to the timeline title");
                } else {
                    if (!track.locale().equals(option.path("locale").asText())) {
                        add(errors, optionPath + ".locale", "TRACK_LOCALE_MISMATCH",
                                "must equal the referenced subtitle track locale");
                    }
                    if (!"APPROVED".equals(track.status()) && !"ACTIVE".equals(track.status())) {
                        add(errors, optionPath + ".subtitleTrackId", "TRACK_NOT_APPROVED",
                                "referenced subtitle track must be APPROVED or ACTIVE");
                    }
                }
            }
        }
        if (approved < 2) {
            add(errors, path + ".options", "TOO_FEW_APPROVED_OPTIONS",
                    "a variation point requires at least two approved options");
        }
        if (defaults != 1) {
            add(errors, path + ".options", "DEFAULT_OPTION_COUNT",
                    "a variation point requires exactly one default option");
        }
    }

    private static void validatePlainText(JsonNode node, String path, List<ErrorDetail> errors) {
        if (!node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                try {
                    PlainText.normalize(path + "." + entry.getKey(), entry.getValue().asText(), 1, 20_000);
                } catch (ApiException e) {
                    add(errors, path + "." + entry.getKey(), "PLAIN_TEXT_REQUIRED",
                            "must be valid plain text without markup or control characters");
                }
            }
        });
    }

    private static void add(List<ErrorDetail> errors, String field, String code, String message) {
        errors.add(new ErrorDetail(field, code, message));
    }

    record SubtitleTrackReference(UUID id, UUID titleId, String locale, String status) {
    }
}
