package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Join command; persistence repeats authorization and capacity checks under the room row lock. */
final class JoinWatchSpaceMutation implements RoomMutation<WatchSpaceDtos.JoinWatchSpaceResponse> {

    private final UUID watchSpaceId;
    private final UUID userId;
    private final String inviteCodeHash;
    private final WatchSpacePersistence persistence;

    JoinWatchSpaceMutation(UUID watchSpaceId, UUID userId, String inviteCodeHash,
                           WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.userId = userId;
        this.inviteCodeHash = inviteCodeHash;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<WatchSpaceDtos.JoinWatchSpaceResponse> apply(
            RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.JoinResult result =
                persistence.join(watchSpaceId, userId, inviteCodeHash, mutationInstant);
        String role = userId.equals(result.state().hostUserId()) ? "HOST" : "VIEWER";
        WatchSpaceDtos.JoinWatchSpaceResponse response = new WatchSpaceDtos.JoinWatchSpaceResponse(
                watchSpaceId, result.membershipId(), userId, result.joinedAt(),
                role, result.alreadyMember(), result.rejoined());
        return new RoomMutationCommit<>(result.state(), response, result.events());
    }
}
