package com.aiwatchspaces.watchspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.infrastructure.db.DbExecutorTestAccess;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class RoomLaneTest {

    @Test
    void reloadsPersistedSnapshotAfterOptimisticConflictAndRejectsCurrentCommand() throws Exception {
        try (DbExecutor executor = DbExecutorTestAccess.create(1, 8)) {
            UUID watchSpaceId = UUID.randomUUID();
            RoomState initialSnapshot = snapshot(watchSpaceId, 2L, 4L);
            RoomState concurrentlyCommittedSnapshot = snapshot(watchSpaceId, 3L, 5L);
            WatchSpacePersistence persistence = mock(WatchSpacePersistence.class);
            RoomBroadcaster broadcaster = mock(RoomBroadcaster.class);
            when(persistence.loadState(watchSpaceId))
                    .thenReturn(initialSnapshot, concurrentlyCommittedSnapshot);

            RoomLane lane = new RoomLane(watchSpaceId, 8, executor, persistence, broadcaster,
                    Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC));

            assertThat(lane.snapshot().block(Duration.ofSeconds(5))).isSameAs(initialSnapshot);

            RoomMutation<String> staleMutation = (current, mutationInstant) -> {
                assertThat(current).isSameAs(initialSnapshot);
                throw new ApiException(ErrorCode.CONFLICT_RETRY,
                        "The room changed while this command was being committed. Retry the request.");
            };
            Mono<String> rejected = Mono.<String>create(sink -> lane.enqueue(staleMutation, sink));

            assertThatThrownBy(() -> rejected.block(Duration.ofSeconds(5)))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.code()).isEqualTo(ErrorCode.CONFLICT_RETRY));

            assertThat(lane.snapshot().block(Duration.ofSeconds(5)))
                    .isSameAs(concurrentlyCommittedSnapshot);
            verify(persistence, times(2)).loadState(watchSpaceId);
            verifyNoInteractions(broadcaster);
        }
    }

    private static RoomState snapshot(UUID watchSpaceId, long stateVersion, long lastSequenceNo) {
        Instant instant = Instant.parse("2026-11-05T12:00:00Z");
        return new RoomState(
                watchSpaceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Recovery test title",
                60_000,
                "Recovery test room",
                RoomState.Privacy.PUBLIC_LINK,
                null,
                null,
                RoomState.Status.LIVE,
                null,
                RoomState.PlaybackState.PAUSED,
                25,
                RoomState.AiVerbosity.NORMAL,
                false,
                false,
                false,
                false,
                null,
                instant,
                instant,
                null,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                instant,
                stateVersion,
                lastSequenceNo,
                instant,
                List.of(),
                Map.of());
    }
}
