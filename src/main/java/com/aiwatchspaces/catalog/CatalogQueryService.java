package com.aiwatchspaces.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for published titles. JDBC is used for this projection so page rows and genre labels
 * are fetched in two bounded queries; it never exposes mutable persistence objects.
 */
@Service
class CatalogQueryService {

    private static final RowMapper<TitleRow> TITLE_ROW = CatalogQueryService::mapTitle;

    private final NamedParameterJdbcTemplate jdbc;

    CatalogQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    PageResponse<TitleSummaryResponse> list(String query, String genre, int page, int size) {
        validatePagination(page, size);
        String normalizedQuery = normalizeFilter(query, "query", 120);
        String normalizedGenre = normalizeFilter(genre, "genre", 80);
        long offset = (long) page * size;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("query", normalizedQuery)
                .addValue("genre", normalizedGenre)
                .addValue("size", size)
                .addValue("offset", offset);

        String filter = """
                FROM titles t
                WHERE t.status = 'PUBLISHED'
                  AND (CAST(:query AS varchar) IS NULL
                       OR lower(t.name) LIKE '%' || lower(CAST(:query AS varchar)) || '%'
                       OR lower(coalesce(t.description, '')) LIKE '%' || lower(CAST(:query AS varchar)) || '%')
                  AND (CAST(:genre AS varchar) IS NULL OR EXISTS (
                       SELECT 1 FROM title_genres tg
                       JOIN genres g ON g.id = tg.genre_id
                       WHERE tg.title_id = t.id AND g.name = CAST(:genre AS varchar)))
                """;
        Long total = jdbc.queryForObject("SELECT count(*) " + filter, parameters, Long.class);
        List<TitleRow> rows = jdbc.query("""
                SELECT t.id, t.slug, t.name, t.description, t.duration_ms,
                       t.video_asset_uri, t.default_locale
                """ + filter + """
                ORDER BY lower(t.name), t.id
                LIMIT :size OFFSET :offset
                """, parameters, TITLE_ROW);

        Map<UUID, List<String>> genres = loadGenres(rows.stream().map(TitleRow::id).toList());
        List<TitleSummaryResponse> items = rows.stream()
                .map(row -> new TitleSummaryResponse(row.id(), row.slug(), row.name(), row.description(),
                        row.durationMs(), row.videoAssetUri(), row.defaultLocale(),
                        genres.getOrDefault(row.id(), List.of())))
                .toList();
        return new PageResponse<>(items, page, size, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    TitleDetailResponse getPublished(UUID titleId) {
        List<TitleRow> rows = jdbc.query("""
                SELECT t.id, t.slug, t.name, t.description, t.duration_ms,
                       t.video_asset_uri, t.default_locale, t.created_at, t.updated_at
                FROM titles t
                WHERE t.id = :id AND t.status = 'PUBLISHED'
                """, Map.of("id", titleId), (rs, rowNum) -> new TitleRow(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("duration_ms"),
                rs.getString("video_asset_uri"),
                rs.getString("default_locale"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()));
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }
        TitleRow row = rows.getFirst();
        return new TitleDetailResponse(row.id(), row.slug(), row.name(), row.description(),
                row.durationMs(), row.videoAssetUri(), row.defaultLocale(),
                loadGenres(List.of(row.id())).getOrDefault(row.id(), List.of()),
                row.createdAt(), row.updatedAt());
    }

    private Map<UUID, List<String>> loadGenres(List<UUID> titleIds) {
        if (titleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query("""
                SELECT tg.title_id, g.name
                FROM title_genres tg
                JOIN genres g ON g.id = tg.genre_id
                WHERE tg.title_id IN (:ids)
                ORDER BY g.name
                """, Map.of("ids", titleIds), rs -> {
            UUID titleId = rs.getObject("title_id", UUID.class);
            result.computeIfAbsent(titleId, ignored -> new ArrayList<>()).add(rs.getString("name"));
        });
        return result.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    private static TitleRow mapTitle(ResultSet rs, int rowNum) throws SQLException {
        return new TitleRow(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("duration_ms"),
                rs.getString("video_asset_uri"),
                rs.getString("default_locale"),
                null,
                null);
    }

    private static void validatePagination(int page, int size) {
        if (page < 0) {
            throw validation("page", "must be greater than or equal to zero");
        }
        if (size < 1 || size > 50) {
            throw validation("size", "must be between 1 and 50");
        }
    }

    private static String normalizeFilter(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw validation(field, "must be at most " + maxLength + " characters");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new ErrorDetail(field, "INVALID_VALUE", message)));
    }

    private record TitleRow(UUID id, String slug, String name, String description, int durationMs,
                            String videoAssetUri, String defaultLocale, java.time.Instant createdAt,
                            java.time.Instant updatedAt) {
    }
}
