package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Host-only lock/unlock mutation for one room lane. */
final class SetWatchSpaceLockMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final UUID actorUserId;
    private final boolean locked;
    private final WatchSpacePersistence persistence;

    SetWatchSpaceLockMutation(UUID watchSpaceId, UUID actorUserId, boolean locked,
                              WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.actorUserId = actorUserId;
        this.locked = locked;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.setLocked(watchSpaceId, actorUserId, locked, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
