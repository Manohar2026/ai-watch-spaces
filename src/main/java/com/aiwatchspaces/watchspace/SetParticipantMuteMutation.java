package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Host-only participant mute/unmute command for one room lane. */
final class SetParticipantMuteMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final UUID actorUserId;
    private final UUID targetUserId;
    private final boolean muted;
    private final WatchSpacePersistence persistence;

    SetParticipantMuteMutation(UUID watchSpaceId, UUID actorUserId, UUID targetUserId,
                               boolean muted, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.muted = muted;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.setMuted(watchSpaceId, actorUserId, targetUserId, muted, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
