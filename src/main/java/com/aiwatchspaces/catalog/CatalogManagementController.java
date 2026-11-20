package com.aiwatchspaces.catalog;

import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Admin-only write API for published catalogue titles. */
@RestController
@RequestMapping("/api/v1/admin/titles")
class CatalogManagementController {

    private final CatalogManagementService management;
    private final DbExecutor db;

    CatalogManagementController(CatalogManagementService management, DbExecutor db) {
        this.management = management;
        this.db = db;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<TitleDetailResponse> create(@RequestBody CreateTitleRequest request, AuthenticatedUser caller) {
        return db.call(() -> management.create(request, caller));
    }

    @DeleteMapping("/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> archive(@PathVariable UUID titleId, AuthenticatedUser caller) {
        return db.call(() -> {
            management.archive(titleId, caller);
            return null;
        }).then();
    }
}
