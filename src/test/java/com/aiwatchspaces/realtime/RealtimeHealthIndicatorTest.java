package com.aiwatchspaces.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.aiwatchspaces.infrastructure.db.DbExecutorTestAccess;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

class RealtimeHealthIndicatorTest {

    @Test
    void upWhenIdle() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 10)) {
            var indicator = new RealtimeHealthIndicator(executor, new RealtimeHealthProperties(0.9));
            assertThat(indicator.health().block().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void outOfServiceWhenShuttingDown() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 10)) {
            var indicator = new RealtimeHealthIndicator(executor, new RealtimeHealthProperties(0.9));
            indicator.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));
            var health = indicator.health().block();
            assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(health.getDetails()).containsEntry("shuttingDown", true);
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

            var indicator = new RealtimeHealthIndicator(executor, new RealtimeHealthProperties(0.9));
            var health = indicator.health().block();
            assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(health.getDetails()).containsEntry("dbExecutorSaturated", true);
            release.countDown();
        }
    }
}
