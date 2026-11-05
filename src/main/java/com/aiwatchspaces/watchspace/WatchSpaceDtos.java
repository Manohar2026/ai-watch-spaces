package com.aiwatchspaces.watchspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response DTOs for watch-space REST endpoints. */
public final class WatchSpaceDtos {

    private WatchSpaceDtos() {
    }

    public record CreateWatchSpaceRequest(
            @NotNull UUID titleId,
            @Size(max = 120) String name,
            String privacy,
            @Min(2) @Max(50) Integer maxParticipants,
            String aiVerbosity,
            Boolean votingEnabled,
            Boolean triviaEnabled,
            Instant scheduledAt) {
    }

    public record JoinByInviteRequest(@NotNull String inviteCode) {
    }

    public record JoinByIdRequest(String inviteCode) {
    }

    public record JoinWatchSpaceResponse(
            UUID watchSpaceId,
            UUID membershipId,
            UUID userId,
            Instant joinedAt,
            String roomRole,
            boolean alreadyMember,
            boolean rejoined) {
    }

    public record RoomSettingsResponse(
            int maxParticipants,
            String aiVerbosity,
            boolean triviaEnabled,
            boolean votingEnabled,
            boolean chatMuted) {
    }

    public record CreateWatchSpaceResponse(
            UUID watchSpaceId,
            String inviteCode,
            RoomState.Status status,
            UUID hostUserId,
            Instant createdAt,
            RoomSettingsResponse settings) {
    }

    /** Public-link non-member projection; intentionally exposes only the documented preview fields. */
    public record WatchSpacePreviewResponse(
            String name,
            String titleName,
            RoomState.Status status,
            int participantCount,
            int maxParticipants,
            boolean locked) {
    }

    /** Full room state returned to a current or previous non-removed member. */
    public record WatchSpaceDetailsResponse(
            UUID watchSpaceId,
            UUID titleId,
            String titleName,
            int titleDurationMs,
            String name,
            RoomState.Privacy privacy,
            RoomState.Status status,
            UUID hostUserId,
            String myRoomRole,
            int participantCount,
            long activeMembershipCount,
            int maxParticipants,
            String aiVerbosity,
            boolean triviaEnabled,
            boolean votingEnabled,
            boolean locked,
            boolean chatMuted,
            Instant scheduledAt,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt,
            RoomState.PlaybackState playbackState,
            int lastPlaybackPositionMs,
            Instant playbackUpdatedAt,
            long stateVersion,
            long lastSequenceNo) {
    }

    public record ParticipantResponse(
            UUID userId,
            String displayName,
            String roomRole,
            boolean connected,
            String connectionQuality,
            boolean muted) {
    }
}
