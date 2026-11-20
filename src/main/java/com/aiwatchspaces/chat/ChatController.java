package com.aiwatchspaces.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.ratelimit.RateLimiter;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.watchspace.RoomMutationLanes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Authenticated room chat history and send endpoints. */
@RestController
@RequestMapping("/api/v1/watch-spaces/{watchSpaceId}/chat")
class ChatController {
    private final ChatService chat;
    private final RoomMutationLanes lanes;
    private final DbExecutor db;
    private final RateLimiter rateLimiter;

    ChatController(ChatService chat, RoomMutationLanes lanes, DbExecutor db, RateLimiter rateLimiter) {
        this.chat = chat;
        this.lanes = lanes;
        this.db = db;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    Mono<List<ChatService.ChatMessage>> history(@PathVariable UUID watchSpaceId, AuthenticatedUser actor,
                                                @RequestParam(defaultValue = "0") long afterSequence,
                                                @RequestParam(defaultValue = "50") int limit) {
        return db.call(() -> chat.history(watchSpaceId, actor, afterSequence, limit));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<ChatService.ChatMessage> send(@PathVariable UUID watchSpaceId, @Valid @RequestBody SendMessageRequest request,
                                       AuthenticatedUser actor) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire("chat:" + watchSpaceId + ":" + actor.userId(),
                10, Duration.ofMinutes(1));
        if (!decision.allowed()) throw ApiException.rateLimited(decision.retryAfterSeconds());
        return lanes.submit(watchSpaceId, () -> chat.send(watchSpaceId, actor, request.body()));
    }

    public record SendMessageRequest(@NotBlank @Size(max = 2000) String body) { }
}

