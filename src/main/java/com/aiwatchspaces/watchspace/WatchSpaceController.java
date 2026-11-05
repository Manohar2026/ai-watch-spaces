package com.aiwatchspaces.watchspace;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
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

    @PostMapping("/join")
    Mono<WatchSpaceDtos.JoinWatchSpaceResponse> joinByInviteCode(
            AuthenticatedUser caller,
            @Valid @RequestBody WatchSpaceDtos.JoinByInviteRequest request,
            ServerHttpRequest http) {
        return watchSpaces.joinByInviteCode(caller, request.inviteCode(), clientIp(http));
    }

    @PostMapping("/{watchSpaceId}/join")
    Mono<WatchSpaceDtos.JoinWatchSpaceResponse> joinById(
            @PathVariable UUID watchSpaceId,
            AuthenticatedUser caller,
            @RequestBody(required = false) WatchSpaceDtos.JoinByIdRequest request,
            ServerHttpRequest http) {
        return watchSpaces.joinById(watchSpaceId, caller,
                request == null ? null : request.inviteCode(), clientIp(http));
    }

    @PostMapping("/{watchSpaceId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> leave(@PathVariable UUID watchSpaceId, AuthenticatedUser caller) {
        return watchSpaces.leave(watchSpaceId, caller);
    }

    @PostMapping("/{watchSpaceId}/start")
    Mono<WatchSpaceDtos.WatchSpaceDetailsResponse> start(
            @PathVariable UUID watchSpaceId, AuthenticatedUser caller) {
        return watchSpaces.start(watchSpaceId, caller);
    }

    @PostMapping("/{watchSpaceId}/end")
    Mono<WatchSpaceDtos.WatchSpaceDetailsResponse> end(
            @PathVariable UUID watchSpaceId, AuthenticatedUser caller) {
        return watchSpaces.end(watchSpaceId, caller);
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

    /** Proxy headers are not trusted in this single-instance local deployment. */
    private static String clientIp(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
