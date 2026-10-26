package com.aiwatchspaces.common.error;

import org.springframework.http.HttpStatus;

/**
 * Closed catalogue of API error codes (SYSTEM_DESIGN §6.1). The HTTP status is fixed per code.
 */
public enum ErrorCode {
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    POSITION_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    CHAT_MARKUP_NOT_ALLOWED(HttpStatus.BAD_REQUEST),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED),

    FORBIDDEN(HttpStatus.FORBIDDEN),
    ADMIN_REQUIRED(HttpStatus.FORBIDDEN),
    NOT_ROOM_HOST(HttpStatus.FORBIDDEN),
    NOT_ROOM_MEMBER(HttpStatus.FORBIDDEN),
    REMOVED_FROM_ROOM(HttpStatus.FORBIDDEN),
    CHAT_MUTED(HttpStatus.FORBIDDEN),
    USER_MISMATCH(HttpStatus.FORBIDDEN),

    NOT_FOUND(HttpStatus.NOT_FOUND),

    ROOM_NOT_LIVE(HttpStatus.CONFLICT),
    ROOM_ENDED(HttpStatus.CONFLICT),
    ROOM_LOCKED(HttpStatus.CONFLICT),
    ROOM_FULL(HttpStatus.CONFLICT),
    INVITE_CODE_INVALID(HttpStatus.CONFLICT),
    HOST_ROOM_LIMIT(HttpStatus.CONFLICT),
    NO_CURRENT_TIMELINE(HttpStatus.CONFLICT),
    INVALID_PLAYBACK_TRANSITION(HttpStatus.CONFLICT),
    VOTING_DISABLED(HttpStatus.CONFLICT),
    VOTE_ALREADY_OPEN(HttpStatus.CONFLICT),
    VOTE_NOT_OPEN(HttpStatus.CONFLICT),
    VOTE_TOO_LATE(HttpStatus.CONFLICT),
    VARIATION_POINT_PASSED(HttpStatus.CONFLICT),
    VARIATION_ALREADY_APPLIED(HttpStatus.CONFLICT),
    OPTION_NOT_ELIGIBLE(HttpStatus.CONFLICT),
    NO_TRIVIA_AVAILABLE(HttpStatus.CONFLICT),
    TIMELINE_NOT_PUBLISHABLE(HttpStatus.CONFLICT),
    CONFLICT_RETRY(HttpStatus.CONFLICT),
    EMAIL_TAKEN(HttpStatus.CONFLICT),

    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),

    TIMELINE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE),
    ROOM_BUSY(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
