package com.aiwatchspaces.common.error;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

import com.aiwatchspaces.common.correlation.CorrelationIdWebFilter;
import com.aiwatchspaces.common.correlation.CorrelationKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.PayloadTooLargeException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Maps every error to the documented envelope (SYSTEM_DESIGN §6.1). Runs before Boot's default
 * {@code ErrorWebExceptionHandler} (order -1). Responses never contain stack traces.
 */
@Component
@Order(-2)
public class GlobalErrorWebExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorWebExceptionHandler.class);

    private final ErrorResponseWriter writer;

    public GlobalErrorWebExceptionHandler(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        Mapped mapped = map(ex);
        if (mapped.status().is5xxServerError() && mapped.code() == ErrorCode.INTERNAL_ERROR) {
            logUnexpected(exchange, ex);
        }
        return writer.write(exchange, mapped.status(), mapped.code(), mapped.message(), mapped.details(),
                mapped.retryAfterSeconds());
    }

    static Mapped map(Throwable ex) {
        if (ex instanceof ApiException api) {
            return new Mapped(api.code().status(), api.code(), api.getMessage(), api.details(), api.retryAfterSeconds());
        }
        if (ex instanceof WebExchangeBindException bind) {
            List<ErrorDetail> details = bind.getFieldErrors().stream().map(GlobalErrorWebExceptionHandler::detail).toList();
            return mapped(ErrorCode.VALIDATION_FAILED, "Request validation failed.", details);
        }
        if (ex instanceof PayloadTooLargeException || hasCause(ex, DataBufferLimitException.class)) {
            return mapped(ErrorCode.PAYLOAD_TOO_LARGE, "Request body is too large.", List.of());
        }
        if (ex instanceof ServerWebInputException) {
            return mapped(ErrorCode.MALFORMED_REQUEST, "Malformed request.", List.of());
        }
        if (ex instanceof RejectedExecutionException) {
            return mapped(ErrorCode.SERVICE_BUSY, "The service is busy. Retry shortly.", List.of());
        }
        if (ex instanceof AuthenticationException) {
            return mapped(ErrorCode.UNAUTHENTICATED, "Authentication is required.", List.of());
        }
        if (ex instanceof AccessDeniedException) {
            return mapped(ErrorCode.FORBIDDEN, "Access denied.", List.of());
        }
        if (ex instanceof ResponseStatusException rse) {
            return fromStatus(rse.getStatusCode());
        }
        return mapped(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", List.of());
    }

    /** Framework status errors. Unlisted 4xx keep their HTTP status and use {@code MALFORMED_REQUEST}. */
    private static Mapped fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> mapped(ErrorCode.UNAUTHENTICATED, "Authentication is required.", List.of());
            case 403 -> mapped(ErrorCode.FORBIDDEN, "Access denied.", List.of());
            case 404 -> mapped(ErrorCode.NOT_FOUND, "Resource not found.", List.of());
            case 413 -> mapped(ErrorCode.PAYLOAD_TOO_LARGE, "Request body is too large.", List.of());
            case 429 -> mapped(ErrorCode.RATE_LIMITED, "Too many requests.", List.of());
            case 503 -> mapped(ErrorCode.SERVICE_BUSY, "The service is busy. Retry shortly.", List.of());
            default -> status.is4xxClientError()
                    ? new Mapped(status, ErrorCode.MALFORMED_REQUEST, "Malformed request.", List.of(), null)
                    : mapped(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", List.of());
        };
    }

    private static ErrorDetail detail(FieldError error) {
        String code = error.getCode() == null ? "INVALID"
                : error.getCode().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        return new ErrorDetail(error.getField(), code, error.getDefaultMessage());
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static Mapped mapped(ErrorCode code, String message, List<ErrorDetail> details) {
        return new Mapped(code.status(), code, message, details, null);
    }

    private static void logUnexpected(ServerWebExchange exchange, Throwable ex) {
        String correlationId = CorrelationIdWebFilter.correlationId(exchange);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationKeys.CORRELATION_ID, correlationId)) {
            log.error("Unhandled error on {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(), ex);
        }
    }

    record Mapped(HttpStatusCode status, ErrorCode code, String message, List<ErrorDetail> details,
                  Long retryAfterSeconds) {
    }
}
