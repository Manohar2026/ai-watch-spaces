package com.aiwatchspaces.catalog;

import java.util.UUID;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Published catalogue reads (SYSTEM_DESIGN §6.3). */
@RestController
@RequestMapping("/api/v1/titles")
class CatalogController {

    private final CatalogQueryService catalog;
    private final DbExecutor db;

    CatalogController(CatalogQueryService catalog, DbExecutor db) {
        this.catalog = catalog;
        this.db = db;
    }

    @GetMapping
    Mono<PageResponse<TitleSummaryResponse>> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return db.call(() -> catalog.list(query, genre, page, size));
    }

    @GetMapping("/{titleId}")
    Mono<TitleDetailResponse> get(@PathVariable UUID titleId) {
        return db.call(() -> catalog.getPublished(titleId));
    }
}
