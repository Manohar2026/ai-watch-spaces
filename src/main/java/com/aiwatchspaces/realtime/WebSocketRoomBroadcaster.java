package com.aiwatchspaces.realtime;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.watchspace.RoomBroadcaster;
import com.aiwatchspaces.watchspace.RoomEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bridges committed watch-space ledger events to the realtime transport.
 * ObjectProvider keeps the watchspace -> broadcaster -> session-registry dependency lazy,
 * avoiding a startup cycle through WatchSpaceRealtimePort and RoomLaneRegistry.
 */
@Component
final class WebSocketRoomBroadcaster implements RoomBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WebSocketRoomBroadcaster.class);

    private final ObjectProvider<WebSocketSessionRegistry> sessions;
    private final JsonMapper json;
    private final Clock clock;

    WebSocketRoomBroadcaster(ObjectProvider<WebSocketSessionRegistry> sessions, JsonMapper json, Clock clock) {
        this.sessions = sessions;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void broadcast(UUID watchSpaceId, List<RoomEvent> committedEvents) {
        if (committedEvents == null || committedEvents.isEmpty()) {
            return;
        }
        WebSocketSessionRegistry registry = sessions.getIfAvailable();
        if (registry == null) {
            return;
        }
        for (RoomEvent event : committedEvents) {
            if (!watchSpaceId.equals(event.watchSpaceId())) {
                log.warn("Skipping cross-room ledger event: requestedRoomId={} eventRoomId={} sequenceNo={}",
                        watchSpaceId, event.watchSpaceId(), event.sequenceNo());
                continue;
            }
            String message;
            try {
                message = json.writeValueAsString(new RoomEventEnvelope(
                        "room.event",
                        watchSpaceId.toString(),
                        clock.millis(),
                        new RoomEventPayload(
                                event.id(),
                                event.sequenceNo(),
                                event.actorUserId(),
                                event.eventType(),
                                event.playbackPositionMs(),
                                event.payload(),
                                event.correlationId(),
                                event.occurredAt().toString())));
            } catch (JacksonException serializationFailure) {
                // The database commit is authoritative. A transport serialization failure must not
                // make the mutation appear to have rolled back or break later events in the batch.
                log.warn("Could not serialize committed room event: roomId={} sequenceNo={} type={}",
                        watchSpaceId, event.sequenceNo(), event.eventType());
                continue;
            }
            registry.broadcast(watchSpaceId, message);
        }
    }

    private record RoomEventEnvelope(String event, String watchSpaceId, long ts, RoomEventPayload payload) {
    }

    private record RoomEventPayload(UUID id, long sequenceNo, UUID actorUserId, String eventType,
                                    Integer playbackPositionMs, Map<String, Object> data,
                                    UUID correlationId, String occurredAt) {
    }
}
