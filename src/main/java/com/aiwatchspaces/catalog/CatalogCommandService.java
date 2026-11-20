package com.aiwatchspaces.catalog;

import java.net.URI;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.text.PlainText;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates immediately published titles for the signed-in MVP catalogue. */
@Service
class CatalogCommandService {
    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogQueryService queries;

    CatalogCommandService(NamedParameterJdbcTemplate jdbc, CatalogQueryService queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    @Transactional
    TitleDetailResponse create(CreateTitleRequest request, AuthenticatedUser actor) {
        String name = PlainText.normalize("name", request.name(), 1, 200);
        String description = request.description() == null || request.description().isBlank()
                ? null : PlainText.normalize("description", request.description(), 1, 5000);
        String videoUri = validateVideoUri(request.videoAssetUri());
        UUID titleId = UUID.randomUUID();
        String slug = uniqueSlug(name, titleId);
        int durationMs = Math.multiplyExact(request.durationSeconds(), 1000);

        jdbc.update("""
                INSERT INTO titles (id, slug, name, description, duration_ms, video_asset_uri,
                                    status, created_by)
                VALUES (:id, :slug, :name, :description, :duration, :videoUri, 'PUBLISHED', :createdBy)
                """, Map.of("id", titleId, "slug", slug, "name", name,
                "description", description == null ? "" : description,
                "duration", durationMs, "videoUri", videoUri, "createdBy", actor.userId()));

        for (String genre : normalizedGenres(request.genres())) {
            jdbc.update("""
                    INSERT INTO genres (id, name) VALUES (:id, :name)
                    ON CONFLICT (name) DO NOTHING
                    """, Map.of("id", UUID.randomUUID(), "name", genre));
            UUID genreId = jdbc.queryForObject("SELECT id FROM genres WHERE name = :name",
                    Map.of("name", genre), UUID.class);
            jdbc.update("INSERT INTO title_genres (title_id, genre_id) VALUES (:titleId, :genreId)",
                    Map.of("titleId", titleId, "genreId", genreId));
        }
        return queries.getPublished(titleId);
    }

    private String uniqueSlug(String name, UUID titleId) {
        String normalized = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (normalized.isBlank()) normalized = "title";
        String base = normalized.substring(0, Math.min(normalized.length(), 145));
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM titles WHERE slug = :slug)",
                Map.of("slug", base), Boolean.class);
        return Boolean.TRUE.equals(exists) ? base + "-" + titleId.toString().substring(0, 8) : base;
    }

    private static List<String> normalizedGenres(List<String> rawGenres) {
        if (rawGenres == null || rawGenres.isEmpty()) return List.of();
        LinkedHashSet<String> genres = new LinkedHashSet<>();
        for (String raw : rawGenres) {
            String genre = PlainText.normalize("genres", raw, 1, 80).toLowerCase(Locale.ROOT);
            genres.add(genre);
        }
        return List.copyOf(genres);
    }

    private static String validateVideoUri(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > 2000) throw invalidVideoUri();
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw invalidVideoUri();
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw invalidVideoUri();
        }
    }

    private static ApiException invalidVideoUri() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new ErrorDetail("videoAssetUri", "INVALID_URI", "must be an absolute HTTP or HTTPS video URL")));
    }
}

