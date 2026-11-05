package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;

/** Issues a fresh invite code; only the successful HTTP response receives the plaintext value. */
final class RotateWatchSpaceInviteCodeMutation
        implements RoomMutation<WatchSpaceDtos.RotateInviteCodeResponse> {

    private static final int MAX_COLLISION_RETRIES = 5;

    private final UUID watchSpaceId;
    private final UUID actorUserId;
    private final WatchSpacePersistence persistence;
    private final InviteCodeService inviteCodes;

    RotateWatchSpaceInviteCodeMutation(UUID watchSpaceId, UUID actorUserId,
                                       WatchSpacePersistence persistence, InviteCodeService inviteCodes) {
        this.watchSpaceId = watchSpaceId;
        this.actorUserId = actorUserId;
        this.persistence = persistence;
        this.inviteCodes = inviteCodes;
    }

    @Override
    public RoomMutationCommit<WatchSpaceDtos.RotateInviteCodeResponse> apply(
            RoomState current, Instant mutationInstant) {
        for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
            String inviteCode = inviteCodes.generate();
            try {
                WatchSpacePersistence.RotationResult result =
                        persistence.rotateInviteCode(watchSpaceId, actorUserId,
                                inviteCodes.hash(inviteCode), mutationInstant);
                return new RoomMutationCommit<>(result.state(),
                        new WatchSpaceDtos.RotateInviteCodeResponse(inviteCode), result.events());
            } catch (DuplicateKeyException collision) {
                // The failed @Transactional call has rolled back; generate another code and retry.
            }
        }
        throw new IllegalStateException("Could not generate a unique invitation code after several attempts.");
    }
}
