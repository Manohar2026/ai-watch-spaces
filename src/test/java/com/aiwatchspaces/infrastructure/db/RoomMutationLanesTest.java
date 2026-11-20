package com.aiwatchspaces.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.aiwatchspaces.watchspace.RoomMutationLanes;
import org.junit.jupiter.api.Test;

/** Regression coverage for a failed mutation not wedging its room's serialized lane. */
class RoomMutationLanesTest {

    @Test
    void emptyMutationCompletesItsRequestAndReleasesTheNextMutation() {
        try (DbExecutor db = new DbExecutor(1, 16)) {
            RoomMutationLanes lanes = new RoomMutationLanes(db);
            UUID roomId = UUID.randomUUID();

            String emptyResult = lanes.submit(roomId, () -> (String) null).block(Duration.ofSeconds(5));
            assertThat(emptyResult).isNull();

            String nextResult = lanes.submit(roomId, () -> "next mutation completed").block(Duration.ofSeconds(5));
            assertThat(nextResult).isEqualTo("next mutation completed");
        }
    }

    @Test
    void nextMutationRunsAfterPreviousMutationFails() throws Exception {
        try (DbExecutor db = new DbExecutor(1, 16)) {
            RoomMutationLanes lanes = new RoomMutationLanes(db);
            UUID roomId = UUID.randomUUID();
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch failFirst = new CountDownLatch(1);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();

            lanes.submit(roomId, () -> {
                firstStarted.countDown();
                if (!failFirst.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for the test to release the first mutation.");
                }
                throw new IllegalArgumentException("Expected test failure.");
            }).subscribe(value -> { }, firstFailure::set);

            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<String> second = lanes.submit(roomId, () -> "second mutation completed").toFuture();
            failFirst.countDown();

            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second mutation completed");
            assertThat(firstFailure.get()).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
