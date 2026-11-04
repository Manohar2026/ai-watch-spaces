package com.aiwatchspaces.catalog;

import java.util.List;
import java.util.UUID;

/** Public shape of a published catalogue title. */
public record TitleSummaryResponse(
        UUID id,
        String slug,
        String name,
        String description,
        int durationMs,
        String videoAssetUri,
        String defaultLocale,
        List<String> genres) {
    public TitleSummaryResponse {
        genres = List.copyOf(genres);
    }
}
