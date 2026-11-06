package com.aiwatchspaces.realtime;

import java.util.concurrent.atomic.AtomicBoolean;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.watchspace.RoomRecoveryStatus;
import com.aiwatchspaces.watchspace.WatchSpaceRealtimePort;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Readiness of the real-time gateway (SYSTEM_DESIGN §11, health key {@code realtime}).
 *
 * <p>Readiness requires the WebSocket mapping, responsive room runtime, completed startup recovery,
 * and unsaturated database and room-lane queues.
 */
@Component("realtimeHealthIndicator")
class RealtimeHealthIndicator implements ReactiveHealthIndicator, ApplicationListener<ContextClosedEvent>, Ordered {

    private final DbExecutor dbExecutor;
    private final RealtimeHealthProperties properties;
    private final RoomRecoveryStatus recoveryStatus;
    private final WatchSpaceRealtimePort rooms;
    private final WebSocketTransportStatus transportStatus;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    RealtimeHealthIndicator(DbExecutor dbExecutor, RealtimeHealthProperties properties,
                            RoomRecoveryStatus recoveryStatus, WatchSpaceRealtimePort rooms,
                            WebSocketTransportStatus transportStatus) {
        this.dbExecutor = dbExecutor;
        this.properties = properties;
        this.recoveryStatus = recoveryStatus;
        this.rooms = rooms;
        this.transportStatus = transportStatus;
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(this::check);
    }

    Health check() {
        int depth = dbExecutor.queueDepth();
        int capacity = dbExecutor.queueCapacity();
        boolean saturated = depth >= Math.ceil(capacity * properties.saturationRatio());
        boolean stopping = shuttingDown.get();
        boolean recoveryComplete = recoveryStatus.isCompleted();
        boolean handlerMappingRegistered = transportStatus.isHandlerMappingRegistered();
        boolean roomRegistryResponsive = rooms.isResponsive();
        boolean roomLaneSaturated = rooms.hasSaturatedLane(properties.saturationRatio());
        Health.Builder builder = (saturated || stopping || !recoveryComplete || !handlerMappingRegistered
                || !roomRegistryResponsive || roomLaneSaturated)
                ? Health.status(Status.OUT_OF_SERVICE) : Health.up();
        return builder
                .withDetail("shuttingDown", stopping)
                .withDetail("startupRecoveryComplete", recoveryComplete)
                .withDetail("webSocketHandlerMappingRegistered", handlerMappingRegistered)
                .withDetail("roomRegistryResponsive", roomRegistryResponsive)
                .withDetail("roomLaneSaturated", roomLaneSaturated)
                .withDetail("dbExecutorQueueDepth", depth)
                .withDetail("dbExecutorQueueCapacity", capacity)
                .withDetail("dbExecutorSaturated", saturated)
                .build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown.set(true);
    }
}
