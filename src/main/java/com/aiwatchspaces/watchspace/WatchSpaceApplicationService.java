package com.aiwatchspaces.watchspace;

import java.util.Arrays;
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
