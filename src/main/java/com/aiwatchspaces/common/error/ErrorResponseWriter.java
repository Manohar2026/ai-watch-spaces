package com.aiwatchspaces.common.error;

import java.util.List;

import com.aiwatchspaces.common.correlation.CorrelationIdWebFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Writes the error envelope to a reactive response. Shared by the exception handler and security handlers. */
@Component
public class ErrorResponseWriter {

    static final String RETRY_AFTER_SECONDS = "1";

    private final JsonMapper jsonMapper;

    public ErrorResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String message) {
        return write(exchange, code.status(), code, message, List.of());
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code, String message,
                            List<ErrorDetail> details) {
        return write(exchange, status, code, message, details, null);
    }

    /** @param retryAfterSeconds value for {@code Retry-After} on 429/503; {@code null} uses the default */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCode code, String message,
                            List<ErrorDetail> details, Long retryAfterSeconds) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        ErrorResponse body = new ErrorResponse(code.name(), message, CorrelationIdWebFilter.correlationId(exchange), details);
        byte[] bytes = jsonMapper.writeValueAsBytes(body);
        response.setStatusCode(status);
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(bytes.length);
        if (status.value() == 429 || status.value() == 503) {
            headers.set(HttpHeaders.RETRY_AFTER,
                    retryAfterSeconds == null ? RETRY_AFTER_SECONDS : String.valueOf(retryAfterSeconds));
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
