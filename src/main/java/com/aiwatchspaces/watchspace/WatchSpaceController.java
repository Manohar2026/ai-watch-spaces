package com.aiwatchspaces.watchspace;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Watch-space lifecycle REST API (SYSTEM_DESIGN §6.4). */
@RestController
@RequestMapping("/api/v1/watch-spaces")
class WatchSpaceController {

    private final WatchSpaceApplicationService watchSpaces;

    WatchSpaceController(WatchSpaceApplicationService watchSpaces) {
        this.watchSpaces = watchSpaces;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<WatchSpaceDtos.CreateWatchSpaceResponse> create(
            AuthenticatedUser caller,
            @Valid @RequestBody WatchSpaceDtos.CreateWatchSpaceRequest request) {
        return watchSpaces.create(caller, request);
    }
}
