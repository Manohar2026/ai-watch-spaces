package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Host end command: close memberships and append ROOM_ENDED as the room's final ledger event. */
final class EndWatchSpaceMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final UUID hostUserId;
    private final WatchSpacePersistence persistence;

    EndWatchSpaceMutation(UUID watchSpaceId, UUID hostUserId, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.hostUserId = hostUserId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.end(watchSpaceId, hostUserId, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
