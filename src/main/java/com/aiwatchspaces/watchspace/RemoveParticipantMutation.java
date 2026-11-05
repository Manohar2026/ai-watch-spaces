package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Host-only removal command; the durable membership close and moderation flag are atomic. */
final class RemoveParticipantMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final UUID actorUserId;
    private final UUID targetUserId;
    private final WatchSpacePersistence persistence;

    RemoveParticipantMutation(UUID watchSpaceId, UUID actorUserId, UUID targetUserId,
                              WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.removeParticipant(watchSpaceId, actorUserId, targetUserId, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
