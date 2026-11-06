package com.aiwatchspaces.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.watchspace.RoomEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.ArgumentCaptor;

class WebSocketRoomBroadcasterTest {

    @Test
    void publishesSafeCommittedEventProjectionToTheMatchingRoom() throws Exception {
        ObjectProvider<WebSocketSessionRegistry> provider = mock(ObjectProvider.class);
        WebSocketSessionRegistry sessions = mock(WebSocketSessionRegistry.class);
        when(provider.getIfAvailable()).thenReturn(sessions);

        JsonMapper json = JsonMapper.builder().build();
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
        UUID roomId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-11-06T11:59:58Z");
        RoomEvent event = new RoomEvent(
                eventId, roomId, 7, actorId, "ROOM_LOCK_CHANGED", null,
                Map.of("locked", true), "private-idempotency-key", correlationId, occurredAt);

        new WebSocketRoomBroadcaster(provider, json, clock).broadcast(roomId, List.of(event));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sessions).broadcast(eq(roomId), message.capture());
        var envelope = json.readTree(message.getValue());
        assertThat(envelope.path("event").asText()).isEqualTo("room.event");
        assertThat(envelope.path("watchSpaceId").asText()).isEqualTo(roomId.toString());
        assertThat(envelope.path("ts").asLong()).isEqualTo(clock.millis());
        assertThat(envelope.path("payload").path("id").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.path("payload").path("sequenceNo").asLong()).isEqualTo(7);
        assertThat(envelope.path("payload").path("eventType").asText()).isEqualTo("ROOM_LOCK_CHANGED");
        assertThat(envelope.path("payload").path("data").path("locked").asBoolean()).isTrue();
        assertThat(envelope.path("payload").path("occurredAt").asText()).isEqualTo(occurredAt.toString());
        assertThat(envelope.path("payload").has("idempotencyKey")).isFalse();
        assertThat(message.getValue()).doesNotContain("private-idempotency-key");
    }
}
