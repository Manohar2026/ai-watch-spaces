package com.aiwatchspaces.realtime;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.watchspace.WatchSpaceRealtimePort;
import com.aiwatchspaces.watchspace.WatchSpaceRealtimeSnapshot;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** First M5 transport slice: ticket-authenticated connection, initial snapshot and bounded sender. */
@Component
final class WatchSpaceWebSocketHandler implements WebSocketHandler {

    private static final int MAX_INBOUND_CHARS = 16 * 1024;
    private static final CloseStatus TOO_LARGE = new CloseStatus(1009, "WebSocket frame is too large.");
    private static final CloseStatus UNSUPPORTED_FRAME = new CloseStatus(4400, "Only text JSON frames are supported.");

    private final WatchSpaceRealtimePort rooms;
    private final WebSocketSessionRegistry sessions;
    private final JsonMapper json;
    private final Clock clock;

    WatchSpaceWebSocketHandler(WatchSpaceRealtimePort rooms, WebSocketSessionRegistry sessions,
                               JsonMapper json, Clock clock) {
        this.rooms = rooms;
        this.sessions = sessions;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(WebSocketTicketHandshakeAuthenticator.SELECTED_PROTOCOL);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UUID watchSpaceId = roomId(session);
        if (watchSpaceId == null) {
            return session.close(new CloseStatus(4404, "Watch Space not found."));
        }
        return session.getHandshakeInfo().getPrincipal()
                .ofType(Authentication.class)
                .map(Authentication::getPrincipal)
                .ofType(AuthenticatedUser.class)
                .switchIfEmpty(Mono.error(new IllegalStateException("Authenticated WebSocket principal is missing.")))
                .flatMap(caller -> rooms.snapshot(watchSpaceId, caller.userId())
                        .flatMap(snapshot -> runSession(session, snapshot, caller)))
                .onErrorResume(failure -> session.close(new CloseStatus(4403, "Room access is no longer valid.")));
    }

    private Mono<Void> runSession(WebSocketSession session, WatchSpaceRealtimeSnapshot snapshot,
                                  AuthenticatedUser caller) {
        WebSocketSessionRegistry.Connection connection =
                sessions.register(snapshot.watchSpaceId(), caller.userId(), session);
        String snapshotJson;
        String historyJson;
        try {
            snapshotJson = json.writeValueAsString(snapshotEnvelope(snapshot, caller,
                    sessions.connectedUserIds(snapshot.watchSpaceId())));
            historyJson = json.writeValueAsString(new OutboundEnvelope(
                    "room.chat.history", snapshot.watchSpaceId().toString(), clock.millis(),
                    new ChatHistory(List.of())));
        } catch (JacksonException serializationFailure) {
            sessions.unregister(connection);
            return session.close(CloseStatus.SERVER_ERROR);
        }

        Flux<WebSocketMessage> outbound = Flux.concat(
                Mono.fromSupplier(() -> session.textMessage(snapshotJson)),
                Mono.fromSupplier(() -> session.textMessage(historyJson)),
                connection.outbound().map(session::textMessage));
        Mono<Void> send = session.send(outbound);
        Mono<Void> receive = session.receive()
                .concatMap(message -> handleInbound(message, session, connection))
                .then();
        return Mono.firstWithSignal(send, receive)
                .doFinally(signal -> sessions.unregister(connection));
    }

    private Mono<Void> handleInbound(WebSocketMessage message, WebSocketSession session,
                                     WebSocketSessionRegistry.Connection connection) {
        if (message.getType() != WebSocketMessage.Type.TEXT) {
            return session.close(UNSUPPORTED_FRAME);
        }
        String payload = message.getPayloadAsText();
        if (payload.length() > MAX_INBOUND_CHARS) {
            return session.close(TOO_LARGE);
        }
        // Playback, chat, sync and token-renewal commands are introduced in their own M5+ slices.
        connection.send(errorEnvelope("UNSUPPORTED_EVENT", "This WebSocket event is not available yet."));
        return Mono.empty();
    }

    private SnapshotEnvelope snapshotEnvelope(WatchSpaceRealtimeSnapshot snapshot, AuthenticatedUser caller,
                                              Set<UUID> connectedUsers) {
        List<ParticipantSnapshot> participants = snapshot.participants().stream()
                .map(participant -> new ParticipantSnapshot(participant.userId(), participant.displayName(),
                        participant.roomRole(), connectedUsers.contains(participant.userId()),
                        "UNKNOWN", participant.muted()))
                .toList();
        WatchSpaceRealtimeSnapshot.Participant you = snapshot.participants().stream()
                .filter(participant -> participant.userId().equals(caller.userId()))
                .findFirst()
                .orElseThrow();

        RoomSnapshot room = new RoomSnapshot(snapshot.name(), snapshot.titleName(), snapshot.status().name(),
                snapshot.hostUserId(), snapshot.sessionCorrelationId(), new RoomSettingsSnapshot(
                snapshot.settings().maxParticipants(), snapshot.settings().aiVerbosity().name(),
                snapshot.settings().triviaEnabled(), snapshot.settings().votingEnabled(),
                snapshot.settings().chatMuted(), snapshot.settings().locked()));
        PlaybackSnapshot playback = new PlaybackSnapshot(snapshot.playbackState().name(),
                snapshot.lastPlaybackPositionMs(), snapshot.lastPlaybackPositionMs() / 1000.0,
                clock.millis(), snapshot.titleDurationMs(), snapshot.stateVersion());
        SnapshotPayload payload = new SnapshotPayload(room, playback, snapshot.lastSequenceNo(),
                connectedUsers.size(), participants,
                new YouSnapshot(you.roomRole(), you.muted()),
                new AuthSnapshot(caller.tokenExpiresAt().toEpochMilli()));
        return new SnapshotEnvelope("room.snapshot", snapshot.watchSpaceId().toString(), clock.millis(), payload);
    }

    private String errorEnvelope(String code, String message) {
        try {
            return json.writeValueAsString(new OutboundEnvelope("room.error", null, clock.millis(),
                    new ErrorPayload(code, message)));
        } catch (JacksonException failure) {
            return "{\"event\":\"room.error\",\"payload\":{\"code\":\"INTERNAL_ERROR\"}}";
        }
    }

    private static UUID roomId(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            return null;
        }
        try {
            return UUID.fromString(path.substring(slash + 1));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private record SnapshotEnvelope(String event, String watchSpaceId, long ts, SnapshotPayload payload) {
    }

    private record OutboundEnvelope(String event, String watchSpaceId, long ts, Object payload) {
    }

    private record SnapshotPayload(RoomSnapshot room, PlaybackSnapshot playback, long lastSequenceNo,
                                   int participantCount, List<ParticipantSnapshot> participants,
                                   YouSnapshot you, AuthSnapshot auth) {
    }

    private record RoomSnapshot(String name, String titleName, String status, UUID hostUserId,
                                UUID sessionCorrelationId, RoomSettingsSnapshot settings) {
    }

    private record RoomSettingsSnapshot(int maxParticipants, String aiVerbosity, boolean triviaEnabled,
                                        boolean votingEnabled, boolean chatMuted, boolean locked) {
    }

    private record PlaybackSnapshot(String state, int positionMs, double positionSeconds,
                                    long serverTimeMs, int durationMs, long stateVersion) {
    }

    private record ParticipantSnapshot(UUID userId, String displayName, String roomRole,
                                       boolean connected, String connectionQuality, boolean muted) {
    }

    private record YouSnapshot(String roomRole, boolean muted) {
    }

    private record AuthSnapshot(long expiresAtMs) {
    }

    private record ChatHistory(List<Object> messages) {
    }

    private record ErrorPayload(String code, String message) {
    }
}
