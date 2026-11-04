package com.aiwatchspaces.timeline;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Published timeline segment returned by GET /titles/{titleId}/timeline. */
public record TimelineResponse(UUID titleId, UUID timelineVersionId, List<Map<String, Object>> events) {
    public TimelineResponse {
        events = List.copyOf(events);
    }
}
