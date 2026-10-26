package com.aiwatchspaces.common.error;

import java.util.List;

/**
 * The documented error envelope: {@code { code, message, correlationId, details[] }}.
 * Never carries stack traces or internal identifiers.
 */
public record ErrorResponse(String code, String message, String correlationId, List<ErrorDetail> details) {

    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
