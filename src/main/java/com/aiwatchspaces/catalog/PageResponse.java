package com.aiwatchspaces.catalog;

import java.util.List;

/** Paginated response shape from SYSTEM_DESIGN §6.1. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
