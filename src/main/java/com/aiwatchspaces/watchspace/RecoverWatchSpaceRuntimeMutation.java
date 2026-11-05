package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Startup system command that restores a persisted live room to the HOST_DISCONNECTED state. */
final class RecoverWatchSpaceRuntimeMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final WatchSpacePersistence persistence;

    RecoverWatchSpaceRuntimeMutation(UUID watchSpaceId, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.recoverAfterRuntimeRestart(watchSpaceId, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
