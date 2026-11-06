package com.aiwatchspaces.watchspace;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
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
class RoomLaneRegistry implements ApplicationListener<ContextClosedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RoomLaneRegistry.class);
    private static final int EXPIRY_SCAN_BATCH_SIZE = 500;

    private final Map<UUID, RoomLane> lanes = new ConcurrentHashMap<>();
    private final int queueCapacity;
    private final DbExecutor db;
    private final WatchSpacePersistence persistence;
    private final RoomBroadcaster broadcaster;
    private final java.time.Clock clock;
    private final Duration unstartedExpiry;
    private volatile boolean shuttingDown;

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
        return enqueue(watchSpaceId, mutation, false);
    }

    private <R> Mono<R> submitDuringShutdown(UUID watchSpaceId, RoomMutation<R> mutation) {
        return enqueue(watchSpaceId, mutation, true);
    }

    private <R> Mono<R> enqueue(UUID watchSpaceId, RoomMutation<R> mutation, boolean shutdownCommand) {
        return Mono.create(resultSink -> {
            // Submission and eviction share this monitor: queued commands cannot be orphaned.
            synchronized (this) {
                if (shuttingDown && !shutdownCommand) {
                    resultSink.error(new ApiException(
                            ErrorCode.SERVICE_BUSY, "The service is shutting down. Retry shortly."));
                    return;
                }
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

    void sessionOpened(UUID watchSpaceId) {
        RoomLane lane;
        synchronized (this) {
            if (shuttingDown) {
                throw new ApiException(ErrorCode.SERVICE_BUSY, "The service is shutting down. Retry shortly.");
            }
            lane = lanes.computeIfAbsent(watchSpaceId,
                    id -> new RoomLane(id, queueCapacity, db, persistence, broadcaster, clock));
        }
        lane.sessionOpened();
    }

    void sessionClosed(UUID watchSpaceId) {
        RoomLane lane;
        synchronized (this) {
            lane = lanes.get(watchSpaceId);
        }
        if (lane != null) {
            lane.sessionClosed();
        }
    }

    @Scheduled(fixedDelay = 60_000L)
    synchronized void evictIdleLanes() {
        if (shuttingDown) {
            return;
        }
        long now = System.nanoTime();
        lanes.entrySet().removeIf(entry -> entry.getValue().isEvictable(now));
    }

    /** Reconciles every persisted LIVE/HOST_DISCONNECTED room through its serialized lane. */
    Mono<Integer> recoverRoomsNow() {
        return db.call(persistence::findRoomsForStartupRecovery)
                .flatMapMany(Flux::fromIterable)
                .concatMap(watchSpaceId -> submit(watchSpaceId,
                                new RecoverWatchSpaceRuntimeMutation(watchSpaceId, persistence))
                        .map(room -> room.status() == RoomState.Status.HOST_DISCONNECTED ? 1 : 0))
                .reduce(0, Integer::sum);
    }

    /** Periodically expires due SCHEDULED rooms through their normal serialized mutation lane. */
    @Scheduled(fixedDelayString = "${aiws.watchspace.expiry-scan-delay-ms:60000}")
    void expireUnstartedRooms() {
        if (shuttingDown) {
            return;
        }
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

    @Override
    public int getOrder() {
        // The readiness indicator is ordered first so health becomes OUT_OF_SERVICE before the drain.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        Set<UUID> roomIds;
        synchronized (this) {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            roomIds = new LinkedHashSet<>(lanes.keySet());
        }

        try {
            List<UUID> persistedLiveRooms = db.call(persistence::findLiveRoomsForShutdown)
                    .block(Duration.ofSeconds(2));
            if (persistedLiveRooms != null) {
                roomIds.addAll(persistedLiveRooms);
            }
        } catch (RuntimeException scanFailure) {
            log.error("Could not list live rooms for graceful shutdown: error={}",
                    scanFailure.getClass().getSimpleName(), scanFailure);
        }

        if (roomIds.isEmpty()) {
            log.info("Graceful shutdown room drain completed: roomCount=0");
            return;
        }

        try {
            int concurrency = Math.min(8, roomIds.size());
            Flux.fromIterable(roomIds)
                    .flatMap(watchSpaceId -> submitDuringShutdown(
                                    watchSpaceId, new PauseWatchSpaceForShutdownMutation(watchSpaceId, persistence))
                            .doOnError(failure -> log.warn(
                                    "Room shutdown pause failed: watchSpaceId={} error={}",
                                    watchSpaceId, failure.getClass().getSimpleName()))
                            .onErrorResume(failure -> Mono.empty()), concurrency)
                    .then()
                    .block(Duration.ofSeconds(8));
            log.info("Graceful shutdown room drain completed: roomCount={}", roomIds.size());
        } catch (RuntimeException drainFailure) {
            log.error("Graceful shutdown room drain did not finish within its 10-second bound: error={}",
                    drainFailure.getClass().getSimpleName(), drainFailure);
        }
    }

    boolean isResponsive() {
        synchronized (this) {
            return !shuttingDown;
        }
    }

    boolean hasSaturatedLane(double saturationRatio) {
        return lanes.values().stream().anyMatch(lane -> lane.isSaturated(saturationRatio));
    }

    int laneCount() {
        return lanes.size();
    }
}
