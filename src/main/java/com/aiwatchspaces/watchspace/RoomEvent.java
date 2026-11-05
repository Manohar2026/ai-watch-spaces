package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A committed, immutable room-ledger event. */
public record RoomEvent(UUID id, UUID watchSpaceId, long sequenceNo, UUID actorUserId,
                        String eventType, Integer playbackPositionMs, Map<String, Object> payload,
                        String idempotencyKey, UUID correlationId, Instant occurredAt) {
    public RoomEvent {
        payload = Map.copyOf(payload);
    }
}
