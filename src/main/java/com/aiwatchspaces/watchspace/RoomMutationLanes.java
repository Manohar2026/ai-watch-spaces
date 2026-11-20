package com.aiwatchspaces.watchspace;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/** Serializes durable room mutations in one bounded queue per room (ADR-005). */
@Component
public final class RoomMutationLanes {

    private static final int QUEUE_CAPACITY = 256;
    private static final int REJECTED = 0;
    private static final int QUEUED = 1;
    private static final int STARTED = 2;

    private final DbExecutor db;
    private final ConcurrentMap<UUID, Lane> lanes = new ConcurrentHashMap<>();

    public RoomMutationLanes(DbExecutor db) {
        this.db = db;
    }

    public <T> Mono<T> submit(UUID roomId, Callable<T> mutation) {
        return Mono.create(sink -> {
            AtomicBoolean accepted = new AtomicBoolean();
            AtomicBoolean start = new AtomicBoolean();
            AtomicReference<Lane> target = new AtomicReference<>();

            // Enqueue under the map's per-key lock. This makes lane retirement safe:
            // a submitter can never retain an old lane while a new lane is created.
            lanes.compute(roomId, (ignored, existing) -> {
                Lane lane = existing == null ? new Lane(roomId) : existing;
                int result = lane.enqueue(new Entry<>(mutation, sink));
                if (result == REJECTED) {
                    return existing;
                }
                accepted.set(true);
                start.set(result == STARTED);
                target.set(lane);
                return lane;
            });

            if (!accepted.get()) {
                sink.error(new ApiException(ErrorCode.ROOM_BUSY, "This room is busy. Retry shortly."));
                return;
            }
            if (start.get()) {
                target.get().runNext();
            }
        });
    }

    private final class Lane {
        private final UUID roomId;
        private final ArrayDeque<Entry<?>> queue = new ArrayDeque<>();
        private boolean running;

        private Lane(UUID roomId) {
            this.roomId = roomId;
        }

        int enqueue(Entry<?> entry) {
            synchronized (this) {
                if (queue.size() >= QUEUE_CAPACITY) {
                    return REJECTED;
                }
                queue.addLast(entry);
                if (!running) {
                    running = true;
                    return STARTED;
                }
                return QUEUED;
            }
        }

        private void runNext() {
            Entry<?> entry;
            synchronized (this) {
                entry = queue.pollFirst();
                if (entry == null) {
                    running = false;
                }
            }

            if (entry == null) {
                // Remove an idle lane so long-lived processes do not retain every room ever seen.
                // The map compute and enqueue operations share the same per-key lock.
                lanes.compute(roomId, (ignored, existing) -> {
                    if (existing != this) {
                        return existing;
                    }
                    synchronized (this) {
                        return !running && queue.isEmpty() ? null : this;
                    }
                });
                return;
            }

            entry.execute(db, this::runNext);
        }
    }

    private record Entry<T>(Callable<T> work, MonoSink<T> sink) {
        void execute(DbExecutor db, Runnable onComplete) {
            // Advance on success, empty completion, or error. Without doFinally, one failed
            // mutation leaves running=true and permanently blocks every later request for the room.
            db.call(work).doFinally(signal -> onComplete.run()).subscribe(sink::success, sink::error);
        }
    }
}
