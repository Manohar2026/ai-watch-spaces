package com.aiwatchspaces.common.correlation;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Accepts {@code X-Correlation-Id} when it is a valid UUID, otherwise generates one; always echoes it
 * (SYSTEM_DESIGN §6.1). Runs before Spring Security so that 401/403 responses carry the id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(CorrelationKeys.HEADER));
        exchange.getAttributes().put(CorrelationKeys.EXCHANGE_ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(CorrelationKeys.HEADER, correlationId);
        return chain.filter(exchange)
                .contextWrite(context -> context.put(CorrelationKeys.CORRELATION_ID, correlationId));
    }

    static String resolve(String supplied) {
        if (supplied != null && CANONICAL_UUID.matcher(supplied).matches()) {
            return supplied.toLowerCase(Locale.ROOT);
        }
        return UUID.randomUUID().toString();
    }

    /** The correlation id stored on the exchange, or {@code null} if the filter has not run. */
    public static String correlationId(ServerWebExchange exchange) {
        return exchange.getAttribute(CorrelationKeys.EXCHANGE_ATTRIBUTE);
    }
}
