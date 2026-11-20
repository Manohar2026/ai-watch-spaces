package com.aiwatchspaces.watchspace.web;

import java.time.Duration;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.ratelimit.RateLimiter;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.watchspace.RoomMutationLanes;
import com.aiwatchspaces.watchspace.WatchSpaceService;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.CreateRoomRequest;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.JoinByCodeRequest;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.RoomCreatedResponse;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.RoomResponse;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.PlaybackRequest;
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

/** Authenticated watch-space lifecycle API (M4). */
@RestController
@RequestMapping("/api/v1/watch-spaces")
class WatchSpaceController {

    private final WatchSpaceService rooms;
    private final RoomMutationLanes lanes;
    private final DbExecutor db;
    private final RateLimiter rateLimiter;

    WatchSpaceController(WatchSpaceService rooms, RoomMutationLanes lanes, DbExecutor db, RateLimiter rateLimiter) {
        this.rooms = rooms;
        this.lanes = lanes;
        this.db = db;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<RoomCreatedResponse> create(@Valid @RequestBody CreateRoomRequest request, AuthenticatedUser actor) {
        UUID roomId = UUID.randomUUID();
        return lanes.submit(roomId, () -> rooms.create(roomId, actor, request));
    }

    @GetMapping("/{watchSpaceId}")
    Mono<RoomResponse> get(@PathVariable UUID watchSpaceId, AuthenticatedUser actor) {
        return db.call(() -> rooms.get(watchSpaceId, actor));
    }

    @PostMapping("/join")
    Mono<RoomResponse> joinByCode(@Valid @RequestBody JoinByCodeRequest request, AuthenticatedUser actor) {
        checkJoinRate(actor);
        return db.call(() -> rooms.roomIdForInvite(request.inviteCode()))
                .flatMap(roomId -> lanes.submit(roomId, () -> rooms.join(roomId, actor, request.inviteCode())));
    }

    @PostMapping("/{watchSpaceId}/join")
    Mono<RoomResponse> joinByLink(@PathVariable UUID watchSpaceId,
                                  @RequestBody(required = false) JoinByCodeRequest request,
                                  AuthenticatedUser actor) {
        checkJoinRate(actor);
        String inviteCode = request == null ? null : request.inviteCode();
        return lanes.submit(watchSpaceId, () -> rooms.join(watchSpaceId, actor, inviteCode));
    }

    @PostMapping("/{watchSpaceId}/leave")
    Mono<RoomResponse> leave(@PathVariable UUID watchSpaceId, AuthenticatedUser actor) {
        return lanes.submit(watchSpaceId, () -> rooms.leave(watchSpaceId, actor));
    }

    @PostMapping("/{watchSpaceId}/start")
    Mono<RoomResponse> start(@PathVariable UUID watchSpaceId, AuthenticatedUser actor) {
        return lanes.submit(watchSpaceId, () -> rooms.start(watchSpaceId, actor));
    }

    @PostMapping("/{watchSpaceId}/end")
    Mono<RoomResponse> end(@PathVariable UUID watchSpaceId, AuthenticatedUser actor) {
        return lanes.submit(watchSpaceId, () -> rooms.end(watchSpaceId, actor));
    }

    @PostMapping("/{watchSpaceId}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> heartbeat(@PathVariable UUID watchSpaceId, AuthenticatedUser actor) {
        return lanes.submit(watchSpaceId, () -> {
            rooms.heartbeat(watchSpaceId, actor);
            return (Void) null;
        });
    }

    @PostMapping("/{watchSpaceId}/playback")
    Mono<RoomResponse> playback(@PathVariable UUID watchSpaceId, @Valid @RequestBody PlaybackRequest request,
                                AuthenticatedUser actor) {
        return lanes.submit(watchSpaceId, () -> rooms.updatePlayback(watchSpaceId, actor, request));
    }

    private void checkJoinRate(AuthenticatedUser actor) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(
                "watch-space-join:" + actor.userId(), 5, Duration.ofMinutes(1));
        if (!decision.allowed()) {
            throw ApiException.rateLimited(decision.retryAfterSeconds());
        }
    }
}

