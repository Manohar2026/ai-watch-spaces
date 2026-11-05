package com.aiwatchspaces.watchspace;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.text.PlainText;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reactive application boundary; room changes are submitted to the serialized mutation lane. */
@Component
class WatchSpaceApplicationService {

    private final RoomLaneRegistry lanes;
    private final WatchSpacePersistence persistence;
    private final InviteCodeService inviteCodes;

    WatchSpaceApplicationService(RoomLaneRegistry lanes, WatchSpacePersistence persistence,
                                 InviteCodeService inviteCodes) {
        this.lanes = lanes;
        this.persistence = persistence;
        this.inviteCodes = inviteCodes;
    }

    Mono<WatchSpaceDtos.CreateWatchSpaceResponse> create(
            AuthenticatedUser caller, WatchSpaceDtos.CreateWatchSpaceRequest request) {
        CreationOptions options = options(request);
        UUID watchSpaceId = UUID.randomUUID();
        CreateWatchSpaceMutation mutation = new CreateWatchSpaceMutation(
                watchSpaceId, caller.userId(), options, persistence, inviteCodes);
        return lanes.submit(watchSpaceId, mutation);
    }

    Mono<Object> get(UUID watchSpaceId, AuthenticatedUser caller) {
        return lanes.snapshot(watchSpaceId).<Object>map(room -> {
            requireNotRemoved(room, caller.userId());
            boolean hasMembership = hasMembership(room, caller.userId());
            if (!hasMembership && room.privacy() == RoomState.Privacy.INVITE_ONLY) {
                throw notFound();
            }
            if (!hasMembership) {
                // The ADR-005-connected socket count is zero until M5 adds the realtime session registry.
                return new WatchSpaceDtos.WatchSpacePreviewResponse(room.name(), room.titleName(), room.status(),
                        0, room.maxParticipants(), room.locked());
            }
            return new WatchSpaceDtos.WatchSpaceDetailsResponse(
                    room.watchSpaceId(), room.titleId(), room.titleName(), room.titleDurationMs(),
                    room.name(), room.privacy(), room.status(), room.hostUserId(),
                    caller.userId().equals(room.hostUserId()) ? "HOST" : "VIEWER",
                    0, room.activeMembershipCount(), room.maxParticipants(), room.aiVerbosity().name(),
                    room.triviaEnabled(), room.votingEnabled(), room.locked(), room.chatMuted(),
                    room.scheduledAt(), room.createdAt(), room.startedAt(), room.endedAt(),
                    room.playbackState(), room.lastPlaybackPositionMs(), room.playbackUpdatedAt(),
                    room.stateVersion(), room.lastSequenceNo());
        });
    }

    Mono<List<WatchSpaceDtos.ParticipantResponse>> participants(UUID watchSpaceId, AuthenticatedUser caller) {
        return lanes.snapshot(watchSpaceId).map(room -> {
            requireMember(room, caller.userId());
            return room.participants().stream()
                    .filter(participant -> participant.leftAt() == null)
                    .map(participant -> {
                        RoomState.Moderation moderation = room.moderation().get(participant.userId());
                        boolean muted = moderation != null && moderation.muted();
                        return new WatchSpaceDtos.ParticipantResponse(
                                participant.userId(), participant.displayName(),
                                participant.userId().equals(room.hostUserId()) ? "HOST" : "VIEWER",
                                false, "UNKNOWN", muted);
                    })
                    .toList();
        });
    }

    private static boolean hasMembership(RoomState room, UUID userId) {
        return room.participants().stream().anyMatch(participant -> participant.userId().equals(userId));
    }

    private static void requireNotRemoved(RoomState room, UUID userId) {
        RoomState.Moderation moderation = room.moderation().get(userId);
        if (moderation != null && moderation.removedAt() != null) {
            throw new ApiException(ErrorCode.REMOVED_FROM_ROOM, "You have been removed from this room.");
        }
    }

    private static void requireMember(RoomState room, UUID userId) {
        requireNotRemoved(room, userId);
        if (!hasMembership(room, userId)) {
            if (room.privacy() == RoomState.Privacy.INVITE_ONLY) {
                throw notFound();
            }
            throw new ApiException(ErrorCode.NOT_ROOM_MEMBER, "You are not a member of this room.");
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
    }

    private static CreationOptions options(WatchSpaceDtos.CreateWatchSpaceRequest request) {
        String name = request.name() == null ? null : PlainText.normalize("name", request.name(), 1, 120);
        RoomState.Privacy privacy = parseEnum(request.privacy(), RoomState.Privacy.class,
                RoomState.Privacy.INVITE_ONLY, "privacy");
        RoomState.AiVerbosity verbosity = parseEnum(request.aiVerbosity(), RoomState.AiVerbosity.class,
                RoomState.AiVerbosity.NORMAL, "aiVerbosity");
        int maxParticipants = request.maxParticipants() == null ? 25 : request.maxParticipants();
        if (maxParticipants < 2 || maxParticipants > 50) {
            throw validation("maxParticipants", "must be between 2 and 50");
        }
        return new CreationOptions(request.titleId(), name, privacy, maxParticipants, verbosity,
                request.triviaEnabled() == null || request.triviaEnabled(),
                request.votingEnabled() == null || request.votingEnabled(), request.scheduledAt());
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, E defaultValue, String field) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw validation(field, "must be one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                java.util.List.of(new ErrorDetail(field, "INVALID_VALUE", message)));
    }

    record CreationOptions(
            UUID titleId,
            String name,
            RoomState.Privacy privacy,
            int maxParticipants,
            RoomState.AiVerbosity aiVerbosity,
            boolean triviaEnabled,
            boolean votingEnabled,
            java.time.Instant scheduledAt) {
    }
}
