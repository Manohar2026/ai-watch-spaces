package com.aiwatchspaces.watchspace;

import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/{watchSpaceId}")
    Mono<Object> get(@PathVariable UUID watchSpaceId, AuthenticatedUser caller) {
        return watchSpaces.get(watchSpaceId, caller);
    }

    @GetMapping("/{watchSpaceId}/participants")
    Mono<List<WatchSpaceDtos.ParticipantResponse>> participants(
            @PathVariable UUID watchSpaceId, AuthenticatedUser caller) {
        return watchSpaces.participants(watchSpaceId, caller);
    }
}
