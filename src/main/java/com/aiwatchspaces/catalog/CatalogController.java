package com.aiwatchspaces.catalog;

import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.ratelimit.RateLimiter;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final CatalogCommandService commands;
    private final DbExecutor db;
    private final RateLimiter rateLimiter;

    CatalogController(CatalogQueryService catalog, CatalogCommandService commands, DbExecutor db, RateLimiter rateLimiter) {
        this.catalog = catalog;
        this.commands = commands;
        this.db = db;
        this.rateLimiter = rateLimiter;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<TitleDetailResponse> create(@Valid @RequestBody CreateTitleRequest request, AuthenticatedUser actor) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire("catalog-create:" + actor.userId(), 10,
                java.time.Duration.ofHours(1));
        if (!decision.allowed()) throw ApiException.rateLimited(decision.retryAfterSeconds());
        return db.call(() -> commands.create(request, actor));
    }
}

