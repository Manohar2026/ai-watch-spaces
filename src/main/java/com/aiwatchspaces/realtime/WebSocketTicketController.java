package com.aiwatchspaces.realtime;

import java.time.Instant;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.ratelimit.RateLimiter;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.watchspace.WatchSpaceRealtimePort;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Mints browser-compatible, one-time tickets after confirming the caller is an active room member. */
@RestController
@RequestMapping("/api/v1/watch-spaces")
class WebSocketTicketController {

    private final WatchSpaceRealtimePort rooms;
    private final WebSocketTicketService tickets;
    private final RateLimiter rateLimiter;
    private final WebSocketTicketRateLimitProperties limits;

    WebSocketTicketController(WatchSpaceRealtimePort rooms, WebSocketTicketService tickets,
                              RateLimiter rateLimiter, WebSocketTicketRateLimitProperties limits) {
        this.rooms = rooms;
        this.tickets = tickets;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/{watchSpaceId}/connection-ticket")
    Mono<WebSocketTicketResponse> issue(
            @PathVariable UUID watchSpaceId, AuthenticatedUser caller) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(
                "realtime:ticket:user:" + caller.userId(),
                limits.perUserLimit(), limits.window());
        if (!decision.allowed()) {
            return Mono.error(ApiException.rateLimited(decision.retryAfterSeconds()));
        }
        return rooms.snapshot(watchSpaceId, caller.userId())
                .map(ignored -> {
                    WebSocketTicketService.IssuedWebSocketTicket ticket = tickets.issue(caller, watchSpaceId);
                    return new WebSocketTicketResponse(ticket.value(), ticket.expiresAt());
                });
    }

    record WebSocketTicketResponse(String ticket, Instant expiresAt) {
        @Override
        public String toString() {
            return "WebSocketTicketResponse[ticket=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }
}
