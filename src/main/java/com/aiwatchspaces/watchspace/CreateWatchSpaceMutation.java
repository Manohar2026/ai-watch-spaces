package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;

/** Create-room command; validation and decision happen in the lane before the write transaction. */
final class CreateWatchSpaceMutation implements RoomMutation<WatchSpaceDtos.CreateWatchSpaceResponse> {

    private final UUID watchSpaceId;
    private final UUID hostUserId;
    private final WatchSpaceApplicationService.CreationOptions options;
    private final WatchSpacePersistence persistence;
    private final InviteCodeService inviteCodes;

    CreateWatchSpaceMutation(UUID watchSpaceId, UUID hostUserId,
                             WatchSpaceApplicationService.CreationOptions options,
                             WatchSpacePersistence persistence, InviteCodeService inviteCodes) {
        this.watchSpaceId = watchSpaceId;
        this.hostUserId = hostUserId;
        this.options = options;
        this.persistence = persistence;
        this.inviteCodes = inviteCodes;
    }

    @Override
    public boolean createsRoom() {
        return true;
    }

    @Override
    public RoomMutationCommit<WatchSpaceDtos.CreateWatchSpaceResponse> apply(
            RoomState current, Instant mutationInstant) {
        if (current != null) {
            throw new ApiException(ErrorCode.CONFLICT_RETRY, "The room already exists. Retry the request.");
        }
        if (options.scheduledAt() != null && !options.scheduledAt().isAfter(mutationInstant)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new ErrorDetail("scheduledAt", "MUST_BE_FUTURE",
                            "must be later than the current time")));
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            String inviteCode = inviteCodes.generate();
            Optional<WatchSpacePersistence.CreationResult> created = persistence.create(
                    watchSpaceId, hostUserId, options.titleId(), options.name(), options.privacy(),
                    options.maxParticipants(), options.aiVerbosity(), options.triviaEnabled(),
                    options.votingEnabled(), options.scheduledAt(), inviteCodes.hash(inviteCode), mutationInstant);
            if (created.isPresent()) {
                WatchSpacePersistence.CreationResult result = created.get();
                RoomState state = result.state();
                WatchSpaceDtos.CreateWatchSpaceResponse response = new WatchSpaceDtos.CreateWatchSpaceResponse(
                        state.watchSpaceId(), inviteCode, state.status(), state.hostUserId(), state.createdAt(),
                        new WatchSpaceDtos.RoomSettingsResponse(state.maxParticipants(), state.aiVerbosity().name(),
                                state.triviaEnabled(), state.votingEnabled(), state.chatMuted()));
                return new RoomMutationCommit<>(state, response, result.events());
            }
        }
        throw new ApiException(ErrorCode.SERVICE_BUSY, "Could not allocate a unique invitation code. Retry shortly.");
    }
}
