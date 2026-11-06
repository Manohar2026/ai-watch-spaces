package com.aiwatchspaces.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authenticates browser WebSocket handshakes without requiring query-string credentials.
 * The realtime module supplies the one-time-ticket implementation.
 */
@FunctionalInterface
public interface WebSocketHandshakeAuthenticator {
    Mono<Authentication> authenticate(ServerWebExchange exchange);
}
