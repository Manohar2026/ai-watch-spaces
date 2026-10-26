package com.aiwatchspaces.common.correlation;

import java.util.List;

/**
 * Keys shared by the Reactor context, the logging MDC and structured log output (SYSTEM_DESIGN §11).
 */
public final class CorrelationKeys {

    public static final String HEADER = "X-Correlation-Id";

    public static final String CORRELATION_ID = "correlationId";
    public static final String SESSION_CORRELATION_ID = "sessionCorrelationId";
    public static final String WATCH_SPACE_ID = "watchSpaceId";
    public static final String USER_ID = "userId";

    /** Every key that propagates between the Reactor context and the MDC. */
    public static final List<String> PROPAGATED = List.of(CORRELATION_ID, SESSION_CORRELATION_ID, WATCH_SPACE_ID, USER_ID);

    /** Exchange attribute holding the request's correlation id (read by error handlers). */
    public static final String EXCHANGE_ATTRIBUTE = CorrelationKeys.class.getName() + ".correlationId";

    private CorrelationKeys() {
    }
}
