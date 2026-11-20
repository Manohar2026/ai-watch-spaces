package com.aiwatchspaces.watchspace;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    private final DbExecutor db;
    private final ConcurrentMap<UUID, Lane> lanes = new ConcurrentHashMap<>();

    public RoomMutationLanes(DbExecutor db) {
        this.db = db;
    }

    public <T> Mono<T> submit(UUID roomId, Callable<T> mutation) {
        return Mono.defer(() -> lanes.computeIfAbsent(roomId, ignored -> new Lane())
                .submit(mutation));
    }

    private final class Lane {
        private final ArrayDeque<Entry<?>> queue = new ArrayDeque<>();
        private boolean running;

        <T> Mono<T> submit(Callable<T> work) {
            return Mono.create(sink -> {
                boolean start;
                synchronized (this) {
                    if (queue.size() >= QUEUE_CAPACITY) {
                        sink.error(new ApiException(ErrorCode.ROOM_BUSY, "This room is busy. Retry shortly."));
                        return;
                    }
                    queue.addLast(new Entry<>(work, sink));
                    start = !running;
                    running = true;
                }
                if (start) {
                    runNext();
                }
            });
        }

        private void runNext() {
            Entry<?> entry;
            synchronized (this) {
                entry = queue.pollFirst();
                if (entry == null) {
                    running = false;
                    return;
                }
            }
            entry.execute(db, this::runNext);
        }
    }

    private record Entry<T>(Callable<T> work, MonoSink<T> sink) {
        void execute(DbExecutor db, Runnable onComplete) {
            db.call(work).subscribe(sink::success, sink::error, onComplete);
        }
    }
}

