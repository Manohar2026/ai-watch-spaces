package com.aiwatchspaces.catalog;

import java.util.List;

/** Input for creating one immediately published catalogue title. Duration is expressed in milliseconds. */
public record CreateTitleRequest(
        String name,
        String description,
        Integer durationMs,
        String videoAssetUri,
        String defaultLocale,
        List<String> genres) {
}
