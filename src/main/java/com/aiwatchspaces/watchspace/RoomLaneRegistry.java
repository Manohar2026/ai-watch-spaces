package com.aiwatchspaces.watchspace;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Lazy registry of independent room lanes, evicted after 10 idle minutes when no session/timer remains. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RoomLaneRegistry {

    private static final Logger log = LoggerFactory.getLogger(RoomLaneRegistry.class);
    private static final int EXPIRY_SCAN_BATCH_SIZE = 500;

    private final Map<UUID, RoomLane> lanes = new ConcurrentHashMap<>();
    private final int queueCapacity;
    private final DbExecutor db;
    private final WatchSpacePersistence persistence;
    private final RoomBroadcaster broadcaster;
    private final java.time.Clock clock;
    private final Duration unstartedExpiry;

    RoomLaneRegistry(
            @Value("${aiws.watchspace.lane-queue-capacity:256}") int queueCapacity,
            DbExecutor db, WatchSpacePersistence persistence, RoomBroadcaster broadcaster,
            java.time.Clock clock,
            @Value("${aiws.watchspace.unstarted-expiry:24h}") Duration unstartedExpiry) {
        if (queueCapacity < 1) {
            throw new IllegalStateException("aiws.watchspace.lane-queue-capacity must be positive.");
        }
        this.queueCapacity = queueCapacity;
        this.db = db;
        this.persistence = persistence;
        this.broadcaster = broadcaster;
        this.clock = clock;
        if (unstartedExpiry == null || unstartedExpiry.isZero() || unstartedExpiry.isNegative()) {
            throw new IllegalStateException("aiws.watchspace.unstarted-expiry must be positive.");
        }
        this.unstartedExpiry = unstartedExpiry;
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

    Mono<RoomState> snapshot(UUID watchSpaceId) {
        RoomLane lane;
        synchronized (this) {
            lane = lanes.computeIfAbsent(watchSpaceId,
                    id -> new RoomLane(id, queueCapacity, db, persistence, broadcaster, clock));
        }
        return lane.snapshot();
    }

    @Scheduled(fixedDelay = 60_000L)
    synchronized void evictIdleLanes() {
        long now = System.nanoTime();
        lanes.entrySet().removeIf(entry -> entry.getValue().isEvictable(now));
    }

    /** Periodically expires due SCHEDULED rooms through their normal serialized mutation lane. */
    @Scheduled(fixedDelayString = "${aiws.watchspace.expiry-scan-delay-ms:60000}")
    void expireUnstartedRooms() {
        expireUnstartedRoomsNow().subscribe(
                count -> {
                    if (count > 0) {
                        log.info("Expired unstarted watch spaces: count={}", count);
                    }
                },
                failure -> log.error("Unstarted watch-space expiry sweep failed: error={}",
                        failure.getClass().getSimpleName()));
    }

    /** Testable entry point for one complete expiry sweep. Each candidate is rechecked under row lock. */
    Mono<Integer> expireUnstartedRoomsNow() {
        Instant scanInstant = Instant.now(clock);
        return db.call(() -> persistence.findScheduledRoomsPastUnstartedExpiry(
                        scanInstant, unstartedExpiry, EXPIRY_SCAN_BATCH_SIZE))
                .flatMapMany(Flux::fromIterable)
                .concatMap(watchSpaceId -> submit(watchSpaceId,
                                new ExpireUnstartedWatchSpaceMutation(watchSpaceId, unstartedExpiry, persistence))
                        .map(room -> room.status() == RoomState.Status.ENDED
                                && "UNSTARTED_EXPIRED".equals(room.endedReason()) ? 1 : 0)
                        .onErrorResume(failure -> {
                            log.warn("Unstarted room expiry failed: watchSpaceId={} error={}",
                                    watchSpaceId, failure.getClass().getSimpleName());
                            return Mono.just(0);
                        }))
                .reduce(0, Integer::sum);
    }

    int laneCount() {
        return lanes.size();
    }
}
