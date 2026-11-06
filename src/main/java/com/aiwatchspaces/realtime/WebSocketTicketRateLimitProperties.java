package com.aiwatchspaces.realtime;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-user ticket minting limit; tickets are short-lived but consume process-local memory. */
@Component
final class WebSocketTicketRateLimitProperties {

    private final int perUserLimit;
    private final Duration window;

    WebSocketTicketRateLimitProperties(
            @Value("${aiws.rate-limits.realtime.ticket-per-user.limit:20}") int perUserLimit,
            @Value("${aiws.rate-limits.realtime.ticket-per-user.window:1m}") Duration window) {
        if (perUserLimit < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("WebSocket ticket rate limit must be positive.");
        }
        this.perUserLimit = perUserLimit;
        this.window = window;
    }

    int perUserLimit() {
        return perUserLimit;
    }

    Duration window() {
        return window;
    }
}
