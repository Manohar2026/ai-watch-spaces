package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Explicit leave command; the durable membership close and ledger append share one transaction. */
final class LeaveWatchSpaceMutation implements RoomMutation<WatchSpaceDtos.LeaveWatchSpaceResponse> {

    private final UUID watchSpaceId;
    private final UUID userId;
    private final WatchSpacePersistence persistence;

    LeaveWatchSpaceMutation(UUID watchSpaceId, UUID userId, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.userId = userId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<WatchSpaceDtos.LeaveWatchSpaceResponse> apply(
            RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.leave(watchSpaceId, userId, mutationInstant);
        RoomState state = result.state();
        WatchSpaceDtos.LeaveWatchSpaceResponse response =
                new WatchSpaceDtos.LeaveWatchSpaceResponse(watchSpaceId, result.membershipId(),
                        result.changedAt(), state.stateVersion(), state.lastSequenceNo());
        return new RoomMutationCommit<>(state, response, result.events());
    }
}
