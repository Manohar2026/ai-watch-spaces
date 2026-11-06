package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Safe immutable projection for the realtime transport. It deliberately excludes invite-code
 * digests and moderation metadata for other users.
 */
public record WatchSpaceRealtimeSnapshot(
        UUID watchSpaceId,
        String name,
        String titleName,
        int titleDurationMs,
        RoomState.Status status,
        UUID hostUserId,
        UUID sessionCorrelationId,
        RoomState.PlaybackState playbackState,
        int lastPlaybackPositionMs,
        Instant playbackUpdatedAt,
        long stateVersion,
        long lastSequenceNo,
        Settings settings,
        List<Participant> participants,
        UUID callerUserId) {

    public WatchSpaceRealtimeSnapshot {
        participants = List.copyOf(participants);
    }

    public record Settings(
            int maxParticipants,
            RoomState.AiVerbosity aiVerbosity,
            boolean triviaEnabled,
            boolean votingEnabled,
            boolean chatMuted,
            boolean locked) {
    }

    public record Participant(
            UUID userId,
            String displayName,
            String roomRole,
            boolean muted) {
    }
}
