package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable authoritative room snapshot. Only a committed room-lane mutation may replace this value. */
public record RoomState(
        UUID watchSpaceId, UUID titleId, UUID hostUserId, String titleName, int titleDurationMs,
        String name, Privacy privacy, String inviteCodeHash, Instant inviteCodeRotatedAt,
        Status status, String endedReason, PlaybackState playbackState, int maxParticipants,
        AiVerbosity aiVerbosity, boolean triviaEnabled, boolean votingEnabled, boolean locked,
        boolean chatMuted, Instant scheduledAt, Instant createdAt, Instant startedAt, Instant endedAt,
        Instant hostDisconnectedAt, UUID sessionCorrelationId, UUID pinnedTimelineVersionId,
        int lastPlaybackPositionMs, Instant playbackUpdatedAt, long stateVersion, long lastSequenceNo,
        Instant mutationInstant, List<Participant> participants, Map<UUID, Moderation> moderation) {

    public RoomState {
        participants = List.copyOf(participants);
        moderation = Map.copyOf(moderation);
    }

    public long activeMembershipCount() {
        return participants.stream().filter(participant -> participant.leftAt() == null).count();
    }

    public enum Privacy { PUBLIC_LINK, INVITE_ONLY }
    public enum Status { SCHEDULED, LIVE, HOST_DISCONNECTED, ENDED }
    public enum PlaybackState { PLAYING, PAUSED, BUFFERING }
    public enum AiVerbosity { MINIMAL, NORMAL, CHATTY }

    public record Participant(UUID membershipId, UUID userId, Instant joinedAt, Instant lastSeenAt,
                              Instant leftAt, String leaveReason) {
    }

    public record Moderation(boolean muted, Instant mutedAt, UUID mutedBy, Instant removedAt,
                             UUID removedBy, Instant updatedAt) {
    }
}
