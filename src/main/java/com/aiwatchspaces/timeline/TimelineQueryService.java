package com.aiwatchspaces.timeline;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.infrastructure.parsing.SafeTreeParsers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Read-only timeline query. Interval events use the overlap predicate
 * startMs <= toMs AND endMs >= fromMs; point events are included when their start is in range.
 */
@Service
class TimelineQueryService {

    private static final RowMapper<TimelineEventRow> EVENT_ROW = TimelineQueryService::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    TimelineQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    TimelineResponse readCurrent(UUID titleId, int fromSeconds, int toSeconds) {
        List<Integer> titles = jdbc.query("""
                SELECT duration_ms FROM titles
                WHERE id = :titleId AND status = 'PUBLISHED'
                """, Map.of("titleId", titleId), (rs, rowNum) -> rs.getInt("duration_ms"));
        if (titles.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }

        List<UUID> versions = jdbc.query("""
                SELECT id FROM title_timeline_versions
                WHERE title_id = :titleId AND status = 'CURRENT'
                """, Map.of("titleId", titleId), (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (versions.isEmpty()) {
            throw new ApiException(ErrorCode.NO_CURRENT_TIMELINE, "The title has no current timeline.");
        }
        UUID versionId = versions.getFirst();
        long fromMs = Math.multiplyExact((long) fromSeconds, 1_000L);
        long toMs = Math.multiplyExact((long) toSeconds, 1_000L);

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("versionId", versionId)
                .addValue("fromMs", fromMs)
                .addValue("toMs", toMs);
        List<TimelineEventRow> rows = jdbc.query("""
                SELECT id, event_key, event_type, start_ms, end_ms, payload::text AS payload
                FROM timeline_events
                WHERE timeline_version_id = :versionId
                  AND start_ms <= :toMs
                  AND ((end_ms IS NOT NULL AND end_ms >= :fromMs)
                       OR (end_ms IS NULL AND start_ms >= :fromMs))
                ORDER BY start_ms, event_key
                """, parameters, EVENT_ROW);

        Map<UUID, List<VariationOptionRow>> options = loadApprovedOptions(
                rows.stream().filter(row -> "VARIATION_POINT".equals(row.type()))
                        .map(TimelineEventRow::id).toList());
        List<Map<String, Object>> events = new ArrayList<>(rows.size());
        for (TimelineEventRow row : rows) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", row.id().toString());
            event.put("eventKey", row.eventKey());
            event.put("type", row.type());
            // API companion field is seconds, with millisecond precision.
            event.put("ts", BigDecimal.valueOf(row.startMs(), 3));
            event.put("startMs", row.startMs());
            event.put("endMs", row.endMs());

            JsonNode payload = SafeTreeParsers.readJsonTree(row.payload());
            if (!payload.isObject()) {
                throw new IllegalStateException("Stored timeline payload must be a JSON object.");
            }
            payload.fields().forEachRemaining(entry -> event.put(entry.getKey(),
                    entry.getValue().isNull() ? null : entry.getValue().asText()));

            if ("VARIATION_POINT".equals(row.type())) {
                event.put("options", options.getOrDefault(row.id(), List.of()).stream()
                        .map(option -> Map.of("id", option.id().toString(), "label", option.label()))
                        .toList());
            }
            events.add(event);
        }
        return new TimelineResponse(titleId, versionId, events);
    }

    private Map<UUID, List<VariationOptionRow>> loadApprovedOptions(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<VariationOptionRow>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT timeline_event_id, id, label
                FROM variation_options
                WHERE timeline_event_id IN (:eventIds) AND is_approved = TRUE
                ORDER BY sort_order, id
                """, Map.of("eventIds", eventIds), rs -> {
            UUID eventId = rs.getObject("timeline_event_id", UUID.class);
            result.computeIfAbsent(eventId, ignored -> new ArrayList<>())
                    .add(new VariationOptionRow(rs.getObject("id", UUID.class), rs.getString("label")));
        });
        return result;
    }

    private static TimelineEventRow mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new TimelineEventRow(
                rs.getObject("id", UUID.class),
                rs.getString("event_key"),
                rs.getString("event_type"),
                rs.getInt("start_ms"),
                rs.getObject("end_ms", Integer.class),
                rs.getString("payload"));
    }

    private record TimelineEventRow(UUID id, String eventKey, String type,
                                    int startMs, Integer endMs, String payload) {
    }

    private record VariationOptionRow(UUID id, String label) {
    }
}
