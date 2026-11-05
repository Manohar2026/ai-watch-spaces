package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Explicit leave command; a prior completed leave is idempotent and does not append another event. */
final class LeaveWatchSpaceMutation implements RoomMutation<Boolean> {

    private final UUID watchSpaceId;
    private final UUID userId;
    private final WatchSpacePersistence persistence;

    LeaveWatchSpaceMutation(UUID watchSpaceId, UUID userId, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.userId = userId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<Boolean> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LeaveResult result = persistence.leave(watchSpaceId, userId, mutationInstant);
        return new RoomMutationCommit<>(result.state(), Boolean.TRUE, result.events());
    }
}
