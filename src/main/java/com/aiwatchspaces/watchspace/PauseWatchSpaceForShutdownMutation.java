package com.aiwatchspaces.watchspace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;

/** Final lane command that freezes a live room before this process stops. */
final class PauseWatchSpaceForShutdownMutation implements RoomMutation<RoomState> {

    private final UUID watchSpaceId;
    private final WatchSpacePersistence persistence;

    PauseWatchSpaceForShutdownMutation(UUID watchSpaceId, WatchSpacePersistence persistence) {
        this.watchSpaceId = watchSpaceId;
        this.persistence = persistence;
    }

    @Override
    public RoomMutationCommit<RoomState> apply(RoomState current, Instant mutationInstant) {
        if (current == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }
        if (current.status() != RoomState.Status.LIVE
                || current.playbackState() == RoomState.PlaybackState.PAUSED) {
            return new RoomMutationCommit<>(current, current, List.of());
        }

        int position = estimatePosition(current, mutationInstant);
        WatchSpacePersistence.LifecycleResult result = persistence.pauseForShutdown(
                watchSpaceId, current.stateVersion(), current.lastSequenceNo(), position, mutationInstant);
        return new RoomMutationCommit<>(result.state(), result.state(), result.events());
    }

    /** Mirrors SYSTEM_DESIGN §4.4: advance only while PLAYING; BUFFERING freezes at its base position. */
    static int estimatePosition(RoomState room, Instant now) {
        long position = room.lastPlaybackPositionMs();
        if (room.playbackState() == RoomState.PlaybackState.PLAYING && room.playbackUpdatedAt() != null) {
            long elapsedMillis = Math.max(0L, Duration.between(room.playbackUpdatedAt(), now).toMillis());
            position += elapsedMillis;
        }
        return (int) Math.max(0L, Math.min(room.titleDurationMs(), position));
    }
}
