package com.aiwatchspaces.realtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.aiwatchspaces.watchspace.WatchSpaceRealtimePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Process-local registry for room sockets. A user has at most one active socket in a room;
 * replacing it closes the older connection with close code 4409.
 */
@Component
final class WebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);
    private static final int OUTBOUND_CAPACITY = 256;
    private static final CloseStatus REPLACED =
            new CloseStatus(4409, "A newer connection replaced this session.");
    private static final CloseStatus SLOW_CONSUMER =
            new CloseStatus(4408, "The outbound message buffer overflowed.");

    private final Map<SessionKey, Connection> sessions = new ConcurrentHashMap<>();
    private final WatchSpaceRealtimePort rooms;

    WebSocketSessionRegistry(WatchSpaceRealtimePort rooms) {
        this.rooms = rooms;
    }

    Connection register(UUID watchSpaceId, UUID userId, WebSocketSession session) {
        SessionKey key = new SessionKey(watchSpaceId, userId);
        Connection next = new Connection(key, session);
        Connection previous = sessions.put(key, next);
        if (previous == null) {
            rooms.sessionOpened(watchSpaceId);
        } else {
            previous.close(REPLACED);
        }
        return next;
    }

    void unregister(Connection connection) {
        if (sessions.remove(connection.key(), connection)) {
            rooms.sessionClosed(connection.key().watchSpaceId());
        }
        connection.complete();
    }

    Set<UUID> connectedUserIds(UUID watchSpaceId) {
        return sessions.keySet().stream()
                .filter(key -> key.watchSpaceId().equals(watchSpaceId))
                .map(SessionKey::userId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    int connectedSessionCount() {
        return sessions.size();
    }

    boolean isResponsive() {
        return sessions.size() >= 0;
    }

    final class Connection {
        private final SessionKey key;
        private final WebSocketSession session;
        private final Sinks.Many<String> outbound =
                Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(OUTBOUND_CAPACITY));

        private Connection(SessionKey key, WebSocketSession session) {
            this.key = key;
            this.session = session;
        }

        SessionKey key() {
            return key;
        }

        Flux<String> outbound() {
            return outbound.asFlux();
        }

        boolean send(String json) {
            Sinks.EmitResult result = outbound.tryEmitNext(json);
            if (result == Sinks.EmitResult.OK) {
                return true;
            }
            if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                close(SLOW_CONSUMER);
            } else if (result != Sinks.EmitResult.FAIL_TERMINATED) {
                log.debug("WebSocket outbound signal rejected: roomId={} userId={} result={}",
                        key.watchSpaceId(), key.userId(), result);
            }
            return false;
        }

        void close(CloseStatus status) {
            outbound.tryEmitComplete();
            session.close(status).subscribe(
                    ignored -> { },
                    failure -> log.debug("WebSocket close failed: roomId={} userId={} error={}",
                            key.watchSpaceId(), key.userId(), failure.getClass().getSimpleName()));
        }

        private void complete() {
            outbound.tryEmitComplete();
        }
    }

    record SessionKey(UUID watchSpaceId, UUID userId) {
    }
}
