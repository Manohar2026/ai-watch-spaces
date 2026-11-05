package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.UUID;

/** Partial host settings update executed in the room's serialized lane. */
final class UpdateWatchSpaceSettingsMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final UUID actorUserId;
    private final WatchSpaceApplicationService.SettingsOptions patch;
    private final WatchSpacePersistence persistence;

    UpdateWatchSpaceSettingsMutation(UUID watchSpaceId, UUID actorUserId,
                                     WatchSpaceApplicationService.SettingsOptions patch,
                                     WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.actorUserId = actorUserId;
        this.patch = patch;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        WatchSpacePersistence.LifecycleResult result =
                persistence.updateSettings(watchSpaceId, actorUserId, patch, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }
}
