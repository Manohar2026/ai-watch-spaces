package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Start command; resolves and pins the current timeline/subtitle snapshot in the lane transaction. */
final class StartWatchSpaceMutation implements RoomMutation<WatchSpaceDtos.RoomLifecycleResponse> {

    private final UUID watchSpaceId;
    private final UUID hostUserId;
    private final WatchSpacePersistence persistence;

    StartWatchSpaceMutation(UUID watchSpaceId, UUID hostUserId, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.hostUserId = hostUserId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<WatchSpaceDtos.RoomLifecycleResponse> apply(
            RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.start(watchSpaceId, hostUserId, mutationInstant);
        RoomState state = result.state();
        WatchSpaceDtos.RoomLifecycleResponse response = new WatchSpaceDtos.RoomLifecycleResponse(
                watchSpaceId, state.status(), result.changedAt(), state.sessionCorrelationId(),
                state.pinnedTimelineVersionId(), state.stateVersion(), state.lastSequenceNo());
        return new RoomMutationCommit<>(state, response, result.events());
    }
}
