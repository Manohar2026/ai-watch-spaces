package com.aiwatchspaces.realtime;

import java.util.concurrent.atomic.AtomicBoolean;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Readiness of the real-time gateway (SYSTEM_DESIGN §11, health key {@code realtime}).
 *
 * <p>M1 stub. Checked now: not shutting down, and the db executor queue below saturation. Added by
 * later milestones: WebSocket handler mapping registered (M5), room registry responsive and lane
 * queues below saturation (M4), startup recovery finished (M4).
 */
@Component("realtimeHealthIndicator")
class RealtimeHealthIndicator implements ReactiveHealthIndicator, ApplicationListener<ContextClosedEvent> {

    private final DbExecutor dbExecutor;
    private final RealtimeHealthProperties properties;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    RealtimeHealthIndicator(DbExecutor dbExecutor, RealtimeHealthProperties properties) {
        this.dbExecutor = dbExecutor;
        this.properties = properties;
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
        Health.Builder builder = (saturated || stopping) ? Health.status(Status.OUT_OF_SERVICE) : Health.up();
        return builder
                .withDetail("shuttingDown", stopping)
                .withDetail("dbExecutorQueueDepth", depth)
                .withDetail("dbExecutorQueueCapacity", capacity)
                .withDetail("dbExecutorSaturated", saturated)
                .build();
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown.set(true);
    }
}
