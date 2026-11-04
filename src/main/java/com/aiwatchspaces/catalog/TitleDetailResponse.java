package com.aiwatchspaces.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full published title details and its normalized genres. */
public record TitleDetailResponse(
        UUID id,
        String slug,
        String name,
        String description,
        int durationMs,
        String videoAssetUri,
        String defaultLocale,
        List<String> genres,
        Instant createdAt,
        Instant updatedAt) {
    public TitleDetailResponse {
        genres = List.copyOf(genres);
    }
}
