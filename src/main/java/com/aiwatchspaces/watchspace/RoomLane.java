package com.aiwatchspaces.watchspace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

/**
 * One FIFO mutation lane for one room. It holds no thread while idle; each active command uses the
 * shared bounded DB executor. State is published only after the writer's transaction has committed.
 */
final class RoomLane {

    private static final Logger log = LoggerFactory.getLogger(RoomLane.class);
    private static final long IDLE_EVICTION_NANOS = Duration.ofMinutes(10).toNanos();

    private final UUID watchSpaceId;
    private final DbExecutor db;
    private final WatchSpacePersistence persistence;
    private final RoomBroadcaster broadcaster;
    private final Clock clock;
    private final Sinks.Many<LaneTask<?>> queue;
    private final AtomicReference<RoomState> state = new AtomicReference<>();
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicInteger connectedSessions = new AtomicInteger();
    private final AtomicInteger pendingTimers = new AtomicInteger();
    private volatile long lastActivityNanos = System.nanoTime();

    RoomLane(UUID watchSpaceId, int queueCapacity, DbExecutor db, WatchSpacePersistence persistence,
             RoomBroadcaster broadcaster, Clock clock) {
        this.watchSpaceId = watchSpaceId;
        this.db = db;
        this.persistence = persistence;
        this.broadcaster = broadcaster;
        this.clock = clock;
        this.queue = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(queueCapacity));
        Flux<LaneTask<?>> commands = queue.asFlux();
        commands.concatMap(this::executeUnchecked, 1).subscribe();
    }

    <R> void enqueue(RoomMutation<R> mutation, MonoSink<R> resultSink) {
        pendingTasks.incrementAndGet();
        lastActivityNanos = System.nanoTime();
        Sinks.EmitResult result = queue.tryEmitNext(new LaneTask<>(mutation, resultSink));
        if (result != Sinks.EmitResult.OK) {
            pendingTasks.decrementAndGet();
            resultSink.error(result == Sinks.EmitResult.FAIL_OVERFLOW
                    ? new ApiException(ErrorCode.ROOM_BUSY, "The room is processing too many commands. Retry shortly.")
                    : new ApiException(ErrorCode.SERVICE_BUSY, "The service is busy. Retry shortly."));
        }
    }

    /**
     * Returns an immutable snapshot without entering the mutation queue. A cold lane hydrates once
     * from PostgreSQL and publishes with CAS; if a concurrent mutation has already published a
     * newer state, that state wins and is returned.
     */
    Mono<RoomState> snapshot() {
        return Mono.defer(() -> {
            RoomState current = state.get();
            if (current != null) {
                lastActivityNanos = System.nanoTime();
                return Mono.just(current);
            }
            return db.call(() -> persistence.loadState(watchSpaceId))
                    .map(loaded -> {
                        state.compareAndSet(null, loaded);
                        lastActivityNanos = System.nanoTime();
                        return state.get();
                    });
        });
    }

    boolean isEvictable(long nowNanos) {
        return pendingTasks.get() == 0 && connectedSessions.get() == 0 && pendingTimers.get() == 0
                && nowNanos - lastActivityNanos >= IDLE_EVICTION_NANOS;
    }

    void sessionOpened() {
        connectedSessions.incrementAndGet();
        lastActivityNanos = System.nanoTime();
    }

    void sessionClosed() {
        connectedSessions.updateAndGet(value -> Math.max(0, value - 1));
        lastActivityNanos = System.nanoTime();
    }

    void timerRegistered() {
        pendingTimers.incrementAndGet();
        lastActivityNanos = System.nanoTime();
    }

    void timerCompleted() {
        pendingTimers.updateAndGet(value -> Math.max(0, value - 1));
        lastActivityNanos = System.nanoTime();
    }

    private Mono<Void> executeUnchecked(LaneTask<?> task) {
        return executeTyped(cast(task));
    }

    @SuppressWarnings("unchecked")
    private static <R> LaneTask<R> cast(LaneTask<?> task) {
        return (LaneTask<R>) task;
    }

    private <R> Mono<Void> executeTyped(LaneTask<R> task) {
        return db.call(() -> {
                    RoomState current = state.get();
                    if (current == null && !task.mutation().createsRoom()) {
                        current = persistence.loadState(watchSpaceId);
                    }
                    // Read Clock exactly once per mutation; keep room timestamps non-decreasing.
                    Instant candidate = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                    Instant mutationInstant = current == null || current.mutationInstant() == null
                            || !candidate.isBefore(current.mutationInstant())
                            ? candidate : current.mutationInstant();
                    return task.mutation().apply(current, mutationInstant);
                })
                .doOnNext(committed -> {
                    // db.call emits only after the transactional writer has returned and committed.
                    state.set(committed.nextState());
                    lastActivityNanos = System.nanoTime();
                    if (!committed.events().isEmpty()) {
                        try {
                            broadcaster.broadcast(watchSpaceId, committed.events());
                        } catch (RuntimeException broadcastFailure) {
                            // The DB commit is authoritative. A failed transport must not roll it back.
                            log.warn("Room broadcast failed after commit: watchSpaceId={} error={}",
                                    watchSpaceId, broadcastFailure.getClass().getSimpleName());
                        }
                    }
                    task.resultSink().success(committed.response());
                })
                .then()
                .onErrorResume(error -> {
                    task.resultSink().error(error);
                    return Mono.empty();
                })
                .doFinally(signal -> {
                    pendingTasks.decrementAndGet();
                    lastActivityNanos = System.nanoTime();
                });
    }

    private record LaneTask<R>(RoomMutation<R> mutation, MonoSink<R> resultSink) {
    }
}
