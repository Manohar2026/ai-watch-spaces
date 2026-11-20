package com.aiwatchspaces.catalog;

import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.security.SystemRole;
import com.aiwatchspaces.common.text.LocaleTags;
import com.aiwatchspaces.common.text.PlainText;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin-only creation and safe removal of published catalogue entries. */
@Service
class CatalogManagementService {

    private static final int MAX_DURATION_MS = 24 * 60 * 60 * 1_000;
    private static final int MAX_GENRES = 12;

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogQueryService catalog;
    private final Clock clock;

    CatalogManagementService(NamedParameterJdbcTemplate jdbc, CatalogQueryService catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    TitleDetailResponse create(CreateTitleRequest request, AuthenticatedUser caller) {
        requireAdmin(caller);
        if (request == null) {
            throw invalid("body", "must not be null");
        }

        String name = PlainText.normalize("name", request.name(), 1, 200);
        String description = request.description() == null || request.description().isBlank()
                ? null : PlainText.normalize("description", request.description(), 1, 2_000);
        if (request.durationMs() == null || request.durationMs() < 1_000
                || request.durationMs() > MAX_DURATION_MS) {
            throw invalid("durationMs", "must be between 1000 and 86400000 milliseconds");
        }
        String videoAssetUri = validateVideoUri(request.videoAssetUri());
        String defaultLocale = request.defaultLocale() == null || request.defaultLocale().isBlank()
                ? "en" : LocaleTags.canonicalize("defaultLocale", request.defaultLocale());

        LinkedHashSet<String> genres = normalizeGenres(request.genres());
        UUID titleId = UUID.randomUUID();
        String slug = slug(name, titleId);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        jdbc.update("""
                INSERT INTO titles
                    (id, slug, name, description, duration_ms, video_asset_uri,
                     default_locale, status, created_by, created_at, updated_at)
                VALUES
                    (:id, :slug, :name, :description, :duration, :videoUri,
                     :locale, 'PUBLISHED', :createdBy, :now, :now)
                """, new MapSqlParameterSource()
                .addValue("id", titleId)
                .addValue("slug", slug)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("duration", request.durationMs())
                .addValue("videoUri", videoAssetUri)
                .addValue("locale", defaultLocale)
                .addValue("createdBy", caller.userId())
                .addValue("now", java.sql.Timestamp.from(now)));

        for (String genre : genres) {
            jdbc.update("""
                    INSERT INTO genres (id, name) VALUES (:id, :name)
                    ON CONFLICT (name) DO NOTHING
                    """, Map.of("id", UUID.randomUUID(), "name", genre));
            UUID genreId = jdbc.queryForObject("SELECT id FROM genres WHERE name = :name",
                    Map.of("name", genre), UUID.class);
            jdbc.update("INSERT INTO title_genres (title_id, genre_id) VALUES (:titleId, :genreId)",
                    Map.of("titleId", titleId, "genreId", genreId));
        }

        return catalog.getPublished(titleId);
    }

    /**
     * Archives instead of physically deleting: rooms, timelines, subtitles, and audit history
     * intentionally retain restrictive foreign keys to titles.
     */
    @Transactional
    void archive(UUID titleId, AuthenticatedUser caller) {
        requireAdmin(caller);
        List<String> statuses = jdbc.query("""
                SELECT status FROM titles WHERE id = :id FOR UPDATE
                """, Map.of("id", titleId), (rs, rowNum) -> rs.getString("status"));
        if (statuses.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Catalogue title not found.");
        }
        if ("ARCHIVED".equals(statuses.getFirst())) {
            return;
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                UPDATE titles SET status = 'ARCHIVED', updated_at = :now
                WHERE id = :id AND status <> 'ARCHIVED'
                """, Map.of("id", titleId, "now", java.sql.Timestamp.from(now)));
    }

    private static void requireAdmin(AuthenticatedUser caller) {
        if (caller.systemRole() != SystemRole.ADMIN) {
            throw new ApiException(ErrorCode.ADMIN_REQUIRED, "Only catalogue administrators can manage titles.");
        }
    }

    private static String validateVideoUri(String value) {
        String normalized = PlainText.normalize("videoAssetUri", value, 1, 2_048);
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw invalid("videoAssetUri", "must be an absolute HTTP or HTTPS media URL");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException && e.getMessage() != null
                    && e.getMessage().contains("Request validation failed")) {
                throw e;
            }
            throw invalid("videoAssetUri", "must be a valid absolute HTTP or HTTPS media URL");
        }
    }

    private static LinkedHashSet<String> normalizeGenres(List<String> values) {
        if (values == null) {
            return new LinkedHashSet<>();
        }
        if (values.size() > MAX_GENRES) {
            throw invalid("genres", "must contain at most " + MAX_GENRES + " genres");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String genre = PlainText.normalize("genres", value, 1, 80).toLowerCase(Locale.ROOT);
            result.add(genre);
        }
        return result;
    }

    private static String slug(String name, UUID id) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "title";
        }
        if (base.length() > 145) {
            base = base.substring(0, 145).replaceAll("-+$", "");
        }
        return base + "-" + id.toString().substring(0, 8);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new ErrorDetail(field, "INVALID_VALUE", message)));
    }
}
