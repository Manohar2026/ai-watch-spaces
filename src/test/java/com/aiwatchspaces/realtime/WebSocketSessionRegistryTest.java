package com.aiwatchspaces.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.aiwatchspaces.watchspace.WatchSpaceRealtimePort;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

class WebSocketSessionRegistryTest {

    @Test
    void broadcastOnlyTargetsSocketsRegisteredForTheRequestedRoom() {
        WatchSpaceRealtimePort rooms = mock(WatchSpaceRealtimePort.class);
        when(rooms.isResponsive()).thenReturn(true);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(rooms);
        UUID roomId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        WebSocketSession roomSocket = mock(WebSocketSession.class);
        WebSocketSession otherRoomSocket = mock(WebSocketSession.class);
        WebSocketSessionRegistry.Connection roomConnection =
                registry.register(roomId, userId, roomSocket);
        WebSocketSessionRegistry.Connection otherRoomConnection =
                registry.register(otherRoomId, userId, otherRoomSocket);

        java.util.List<String> roomMessages = new java.util.ArrayList<>();
        java.util.List<String> otherRoomMessages = new java.util.ArrayList<>();
        roomConnection.outbound().subscribe(roomMessages::add);
        otherRoomConnection.outbound().subscribe(otherRoomMessages::add);

        int recipients = registry.broadcast(roomId, "room.event.frame");

        assertThat(recipients).isEqualTo(1);
        assertThat(roomMessages).containsExactly("room.event.frame");
        assertThat(otherRoomMessages).isEmpty();

        registry.unregister(roomConnection);
        registry.unregister(otherRoomConnection);
    }

    @Test
    void newerConnectionReplacesOlderSocketWithoutDoubleCountingTheUser() {
        WatchSpaceRealtimePort rooms = mock(WatchSpaceRealtimePort.class);
        when(rooms.isResponsive()).thenReturn(true);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(rooms);
        WebSocketSession firstSession = mock(WebSocketSession.class);
        WebSocketSession secondSession = mock(WebSocketSession.class);
        when(firstSession.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        when(secondSession.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        WebSocketSessionRegistry.Connection first = registry.register(roomId, userId, firstSession);
        WebSocketSessionRegistry.Connection second = registry.register(roomId, userId, secondSession);

        var closeStatus = forClass(CloseStatus.class);
        verify(firstSession).close(closeStatus.capture());
        assertThat(closeStatus.getValue().getCode()).isEqualTo(4409);
        assertThat(registry.connectedUserIds(roomId)).containsExactly(userId);
        assertThat(registry.connectedSessionCount()).isEqualTo(1);
        verify(rooms).sessionOpened(roomId);

        registry.unregister(first);
        verify(rooms, never()).sessionClosed(roomId);
        assertThat(registry.connectedUserIds(roomId)).containsExactly(userId);

        registry.unregister(second);
        verify(rooms).sessionClosed(roomId);
        assertThat(registry.connectedUserIds(roomId)).isEmpty();
        assertThat(registry.connectedSessionCount()).isZero();
    }
}
