package com.aiwatchspaces.watchspace;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

/** Lazy registry of independent room lanes, evicted after 10 idle minutes when no session/timer remains. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RoomLaneRegistry {

    private final Map<UUID, RoomLane> lanes = new ConcurrentHashMap<>();
    private final int queueCapacity;
    private final DbExecutor db;
    private final WatchSpacePersistence persistence;
    private final RoomBroadcaster broadcaster;
    private final java.time.Clock clock;

    RoomLaneRegistry(
            @Value("${aiws.watchspace.lane-queue-capacity:256}") int queueCapacity,
            DbExecutor db, WatchSpacePersistence persistence, RoomBroadcaster broadcaster,
            java.time.Clock clock) {
        if (queueCapacity < 1) {
            throw new IllegalStateException("aiws.watchspace.lane-queue-capacity must be positive.");
        }
        this.queueCapacity = queueCapacity;
        this.db = db;
        this.persistence = persistence;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    <R> Mono<R> submit(UUID watchSpaceId, RoomMutation<R> mutation) {
        return Mono.create(resultSink -> {
            // Submission and eviction share this monitor: queued commands cannot be orphaned.
            synchronized (this) {
                RoomLane lane = lanes.computeIfAbsent(watchSpaceId,
                        id -> new RoomLane(id, queueCapacity, db, persistence, broadcaster, clock));
                lane.enqueue(mutation, resultSink);
            }
        });
    }

    @Scheduled(fixedDelay = 60_000L)
    synchronized void evictIdleLanes() {
        long now = System.nanoTime();
        lanes.entrySet().removeIf(entry -> entry.getValue().isEvictable(now));
    }

    int laneCount() {
        return lanes.size();
    }
}
