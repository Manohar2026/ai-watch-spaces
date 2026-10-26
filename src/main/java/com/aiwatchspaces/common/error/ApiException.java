package com.aiwatchspaces.common.error;

import java.util.List;

/** A typed application error that maps to the error envelope with the code's fixed HTTP status. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<ErrorDetail> details;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<ErrorDetail> details) {
        this(code, message, details, null);
    }

    private ApiException(ErrorCode code, String message, List<ErrorDetail> details, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** {@code 429 RATE_LIMITED} with the number of seconds for {@code Retry-After}. */
    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(ErrorCode.RATE_LIMITED, "Too many requests. Retry later.", List.of(),
                Math.max(1, retryAfterSeconds));
    }

    public ErrorCode code() {
        return code;
    }

    public List<ErrorDetail> details() {
        return details;
    }

    /** Seconds for the {@code Retry-After} header, or {@code null} for the default. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
