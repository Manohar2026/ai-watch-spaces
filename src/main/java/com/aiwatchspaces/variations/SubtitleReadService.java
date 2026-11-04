package com.aiwatchspaces.variations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.text.LocaleTags;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to active subtitle track metadata. */
@Service
class SubtitleReadService {

    private final NamedParameterJdbcTemplate jdbc;

    SubtitleReadService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    SubtitleTrackResponse getActive(UUID titleId, String requestedLocale) {
        List<String> defaultLocales = jdbc.query("""
                SELECT default_locale FROM titles
                WHERE id = :titleId AND status = 'PUBLISHED'
                """, Map.of("titleId", titleId), (rs, rowNum) -> rs.getString("default_locale"));
        if (defaultLocales.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }
        String locale = requestedLocale == null || requestedLocale.isBlank()
                ? defaultLocales.getFirst()
                : LocaleTags.canonicalize("locale", requestedLocale);

        List<SubtitleTrackResponse> tracks = jdbc.query("""
                SELECT s.id, s.locale, s.version_number, s.asset_uri, s.checksum
                FROM subtitle_tracks s
                JOIN titles t ON t.id = s.title_id
                WHERE s.title_id = :titleId
                  AND t.status = 'PUBLISHED'
                  AND s.locale = :locale
                  AND s.status = 'ACTIVE'
                """, Map.of("titleId", titleId, "locale", locale),
                (rs, rowNum) -> new SubtitleTrackResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("locale"),
                        rs.getInt("version_number"),
                        rs.getString("asset_uri"),
                        rs.getString("checksum")));
        if (tracks.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }
        return tracks.getFirst();
    }
}
