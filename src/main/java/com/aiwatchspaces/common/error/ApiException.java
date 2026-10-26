package com.aiwatchspaces.common.error;

import java.util.List;

/** A typed application error that maps to the error envelope with the code's fixed HTTP status. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<ErrorDetail> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<ErrorDetail> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public List<ErrorDetail> details() {
        return details;
    }
}
