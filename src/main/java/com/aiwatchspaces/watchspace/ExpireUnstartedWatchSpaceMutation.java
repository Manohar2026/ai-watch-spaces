package com.aiwatchspaces.watchspace;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** System command that expires a SCHEDULED room after scheduled_at (or created_at) plus its TTL. */
final class ExpireUnstartedWatchSpaceMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final Duration unstartedExpiry;
    private final WatchSpacePersistence persistence;

    ExpireUnstartedWatchSpaceMutation(UUID watchSpaceId, Duration unstartedExpiry,
                                      WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.unstartedExpiry = unstartedExpiry;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.expireUnstartedRoom(watchSpaceId, mutationInstant, unstartedExpiry);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
