package com.aiwatchspaces.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.aiwatchspaces.infrastructure.db.DbExecutorTestAccess;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.watchspace.RoomRecoveryStatus;
import com.aiwatchspaces.watchspace.WatchSpaceRealtimePort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

class RealtimeHealthIndicatorTest {

    @Test
    void outOfServiceUntilWebSocketMappingIsRegistered() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 10)) {
            RoomRecoveryStatus recovery = new RoomRecoveryStatus();
            recovery.markCompleted();
            WebSocketTransportStatus transport = new WebSocketTransportStatus();
            var indicator = new RealtimeHealthIndicator(
                    executor, new RealtimeHealthProperties(0.9), recovery, healthyRooms(), transport);

            assertThat(indicator.health().block().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(indicator.health().block().getDetails())
                    .containsEntry("webSocketHandlerMappingRegistered", false);

            transport.markHandlerMappingRegistered();
            assertThat(indicator.health().block().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void outOfServiceWhenRoomRuntimeIsUnresponsiveOrLaneIsSaturated() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 10)) {
            RoomRecoveryStatus recovery = new RoomRecoveryStatus();
            recovery.markCompleted();
            WebSocketTransportStatus transport = readyTransport();
            WatchSpaceRealtimePort rooms = mock(WatchSpaceRealtimePort.class);
            when(rooms.isResponsive()).thenReturn(false);
            when(rooms.hasSaturatedLane(anyDouble())).thenReturn(true);
            var indicator = new RealtimeHealthIndicator(
                    executor, new RealtimeHealthProperties(0.9), recovery, rooms, transport);

            var health = indicator.health().block();
            assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(health.getDetails())
                    .containsEntry("roomRegistryResponsive", false)
                    .containsEntry("roomLaneSaturated", true);
        }
    }

    private static WebSocketTransportStatus readyTransport() {
        WebSocketTransportStatus status = new WebSocketTransportStatus();
        status.markHandlerMappingRegistered();
        return status;
    }

    private static WatchSpaceRealtimePort healthyRooms() {
        WatchSpaceRealtimePort rooms = mock(WatchSpaceRealtimePort.class);
        when(rooms.isResponsive()).thenReturn(true);
        when(rooms.hasSaturatedLane(anyDouble())).thenReturn(false);
        return rooms;
    }

    @Test
    void upWhenIdle() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 10)) {
            RoomRecoveryStatus recovery = new RoomRecoveryStatus();
            recovery.markCompleted();
            var indicator = new RealtimeHealthIndicator(
                    executor, new RealtimeHealthProperties(0.9), recovery, healthyRooms(), readyTransport());
            assertThat(indicator.health().block().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void outOfServiceWhenShuttingDown() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 10)) {
            RoomRecoveryStatus recovery = new RoomRecoveryStatus();
            recovery.markCompleted();
            var indicator = new RealtimeHealthIndicator(
                    executor, new RealtimeHealthProperties(0.9), recovery);
            indicator.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));
            var health = indicator.health().block();
            assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(health.getDetails()).containsEntry("shuttingDown", true);
        }
    }

    @Test
    void outOfServiceUntilStartupRecoveryCompletes() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 10)) {
            RoomRecoveryStatus recovery = new RoomRecoveryStatus();
            var indicator = new RealtimeHealthIndicator(
                    executor, new RealtimeHealthProperties(0.9), recovery);

            assertThat(indicator.health().block().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(indicator.health().block().getDetails())
                    .containsEntry("startupRecoveryComplete", false);

            recovery.markCompleted();
            assertThat(indicator.health().block().getStatus()).isEqualTo(Status.UP);
            assertThat(indicator.health().block().getDetails())
                    .containsEntry("startupRecoveryComplete", true);
        }
    }

    @Test
    void outOfServiceWhenDbExecutorQueueIsSaturated() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 2)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.call(() -> {
                running.countDown();
                return release.await(10, TimeUnit.SECONDS);
            }).subscribe();
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            executor.call(() -> 1).subscribe();
            executor.call(() -> 2).subscribe();

            RoomRecoveryStatus recovery = new RoomRecoveryStatus();
            recovery.markCompleted();
            var indicator = new RealtimeHealthIndicator(
                    executor, new RealtimeHealthProperties(0.9), recovery);
            var health = indicator.health().block();
            assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(health.getDetails()).containsEntry("dbExecutorSaturated", true);
            release.countDown();
        }
    }
}
