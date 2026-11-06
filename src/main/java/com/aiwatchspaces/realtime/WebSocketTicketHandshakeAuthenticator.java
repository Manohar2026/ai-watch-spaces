package com.aiwatchspaces.realtime;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.security.SystemRole;
import com.aiwatchspaces.common.security.WebSocketHandshakeAuthenticator;
import com.aiwatchspaces.watchspace.WatchSpaceRealtimePort;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Validates the one-time ticket carried in a non-selected WebSocket subprotocol value. */
@Component
final class WebSocketTicketHandshakeAuthenticator implements WebSocketHandshakeAuthenticator {

    static final String SELECTED_PROTOCOL = "aiws.v1";
    static final String TICKET_PROTOCOL_PREFIX = "aiws.ticket.";

    private final WebSocketTicketService tickets;
    private final WatchSpaceRealtimePort rooms;

    WebSocketTicketHandshakeAuthenticator(WebSocketTicketService tickets, WatchSpaceRealtimePort rooms) {
        this.tickets = tickets;
        this.rooms = rooms;
    }

    @Override
    public Mono<Authentication> authenticate(ServerWebExchange exchange) {
        List<String> offered = exchange.getRequest().getHeaders().getOrEmpty("Sec-WebSocket-Protocol").stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (!offered.contains(SELECTED_PROTOCOL)) {
            return Mono.error(new BadCredentialsException("The required WebSocket subprotocol was not offered."));
        }
        List<String> credentials = offered.stream()
                .filter(value -> value.startsWith(TICKET_PROTOCOL_PREFIX))
                .map(value -> value.substring(TICKET_PROTOCOL_PREFIX.length()))
                .toList();
        if (credentials.size() != 1 || credentials.getFirst().isBlank()) {
            return Mono.error(new BadCredentialsException("A single-use WebSocket ticket is required."));
        }

        UUID roomId = roomId(exchange);
        if (roomId == null) {
            return Mono.error(new BadCredentialsException("The WebSocket room path is invalid."));
        }
        return Mono.justOrEmpty(tickets.consume(credentials.getFirst(), roomId))
                .switchIfEmpty(Mono.error(new BadCredentialsException("The WebSocket ticket is invalid or expired.")))
                .flatMap(user -> rooms.snapshot(roomId, user.userId())
                        .thenReturn(authentication(user)));
    }

    private static Authentication authentication(AuthenticatedUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority(SystemRole.valueOf(user.systemRole().name()).authority())));
    }

    private static UUID roomId(ServerWebExchange exchange) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        String value = path.elements().isEmpty() ? "" : path.elements().getLast().value();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
