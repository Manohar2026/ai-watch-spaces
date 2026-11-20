package com.aiwatchspaces.watchspace.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WatchSpaceDtos {

    private WatchSpaceDtos() {
    }

    public record CreateRoomRequest(@NotNull UUID titleId,
                                    @Size(max = 120) String name,
                                    @Size(max = 20) String privacy,
                                    @Min(2) @Max(50) Integer maxParticipants) {
    }

    public record JoinByCodeRequest(@NotBlank @Size(max = 16) String inviteCode) {
        @Override
        public String toString() {
            return "JoinByCodeRequest[inviteCode=<redacted>]";
        }
    }

    public record RoomCreatedResponse(RoomResponse room, String inviteCode) {
        @Override
        public String toString() {
            return "RoomCreatedResponse[room=" + room.watchSpaceId() + ", inviteCode=<redacted>]";
        }
    }

    public record RoomResponse(UUID watchSpaceId,
                               UUID titleId,
                               String titleName,
                               String videoAssetUri,
                               int durationMs,
                               String name,
                               String privacy,
                               String status,
                               UUID hostUserId,
                               String myRole,
                               int participantCount,
                               int maxParticipants,
                               boolean locked,
                               String playbackState,
                               int playbackPositionMs,
                               Instant playbackUpdatedAt,
                               long stateVersion,
                               long lastSequenceNo,
                               Instant createdAt,
                               Instant startedAt,
                               Instant endedAt,
                               List<RoomParticipantResponse> participants) {
        public RoomResponse {
            participants = List.copyOf(participants);
        }
    }

    public record RoomParticipantResponse(UUID userId, String displayName, String roomRole) {
    }

    public record RoomIdResponse(UUID watchSpaceId) {
    }
}

