package com.aiwatchspaces.watchspace;

import java.time.Instant;
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
}
