package com.aiwatchspaces.infrastructure.db;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The single bounded executor for all JDBC/JPA work (ADR-003 §2–§3, CLAUDE.md guardrail 7).
 *
 * <p>Reactive adapters wrap a blocking service call exactly once with {@link #call(Callable)}. The
 * blocking method itself returns plain values/DTOs and owns its transaction; no transaction spans
 * a thread hop. Correlation values from the Reactor context are restored into the MDC on the
 * {@code db-*} thread for the duration of the call.
 */
public final class DbExecutor implements AutoCloseable {

    public static final String THREAD_PREFIX = "db-";

    private static final ContextSnapshotFactory SNAPSHOTS =
            ContextSnapshotFactory.builder().clearMissing(true).build();

    private final ThreadPoolExecutor executor;
    private final Scheduler scheduler;
    private final int queueCapacity;

    DbExecutor(int threads, int queueCapacity) {
        this.queueCapacity = queueCapacity;
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new DbThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        this.executor.prestartAllCoreThreads();
        this.scheduler = Schedulers.fromExecutorService(executor, "db");
    }

    /**
     * Runs blocking work on a {@code db-*} thread. A {@code null} result completes empty.
     * A full queue fails with {@code 503 SERVICE_BUSY}.
     */
    public <T> Mono<T> call(Callable<T> work) {
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {
                    try (ContextSnapshot.Scope ignored = SNAPSHOTS.setThreadLocalsFrom(context)) {
                        return work.call();
                    }
                }))
                .subscribeOn(scheduler)
                .onErrorMap(RejectedExecutionException.class,
                        e -> new ApiException(ErrorCode.SERVICE_BUSY, "The service is busy. Retry shortly."));
    }

    /** The Reactor view of this executor, for the few places that need a {@link Scheduler} directly. */
    public Scheduler scheduler() {
        return scheduler;
    }

    public int threadCount() {
        return executor.getMaximumPoolSize();
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public int activeThreads() {
        return executor.getActiveCount();
    }

    @Override
    public void close() throws InterruptedException {
        scheduler.dispose();
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    public static boolean isDbThread(Thread thread) {
        return thread.getName().startsWith(THREAD_PREFIX);
    }

    private static final class DbThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, THREAD_PREFIX + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }
}
