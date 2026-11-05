package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Host start command: pin the current timeline/subtitles and enter LIVE in one commit. */
final class StartWatchSpaceMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final UUID hostUserId;
    private final WatchSpacePersistence persistence;

    StartWatchSpaceMutation(UUID watchSpaceId, UUID hostUserId, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.hostUserId = hostUserId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.start(watchSpaceId, hostUserId, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
