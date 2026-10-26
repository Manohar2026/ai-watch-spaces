package com.aiwatchspaces.support;

import java.util.Map;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.m1.JsonbProbeRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Test-only endpoints that exercise M1 infrastructure (db executor, MDC propagation, error
 * envelope). Mounted under {@code /m1-probe} with a test security chain that permits them.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(M1ProbeConfiguration.ProbeController.class)
public class M1ProbeConfiguration {

    static final String CORRELATION_ID = "correlationId";

    @Bean
    static ConnectionThreadGuard connectionThreadGuard() {
        return new ConnectionThreadGuard();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain probeSecurity(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/m1-probe/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                // Same stateless repository as the production chain: the default session-backed one
                // would hop to Reactor's parallel scheduler via the in-memory WebSession store.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    public record EchoRequest(@NotBlank String name, @Size(max = 5) String tag) {
    }

    @RestController
    @RequestMapping("/m1-probe")
    static class ProbeController {

        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

        private final DbExecutor dbExecutor;
        private final JdbcTemplate jdbcTemplate;
        private final TransactionTemplate transactionTemplate;
        private final JsonbProbeRepository repository;

        ProbeController(DbExecutor dbExecutor, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                        JsonbProbeRepository repository) {
            this.dbExecutor = dbExecutor;
            this.jdbcTemplate = jdbcTemplate;
            this.transactionTemplate = transactionTemplate;
            this.repository = repository;
        }

        /** Correct pattern: blocking JDBC and JPA wrapped once at the adapter edge. */
        @GetMapping("/db")
        Mono<Map<String, Object>> db() {
            return dbExecutor.call(() -> transactionTemplate.execute(status -> Map.<String, Object>of(
                    "select1", jdbcTemplate.queryForObject("SELECT 1", Integer.class),
                    "probeRows", repository.count(),
                    "thread", Thread.currentThread().getName())));
        }

        /** Deliberate violation used to prove the guard detects it. */
        @GetMapping("/db-on-event-loop")
        Mono<Map<String, Object>> dbOnEventLoop() {
            return Mono.fromSupplier(() -> Map.of(
                    "select1", jdbcTemplate.queryForObject("SELECT 1", Integer.class),
                    "thread", Thread.currentThread().getName(),
                    "nonBlockingThread", Schedulers.isInNonBlockingThread()));
        }

        @GetMapping("/mdc")
        Mono<Map<String, String>> mdc() {
            return Mono.just("start")
                    .map(ignored -> {
                        log.info("m1-probe netty step");
                        return new String[] {Thread.currentThread().getName(), String.valueOf(MDC.get(CORRELATION_ID))};
                    })
                    .flatMap(netty -> dbExecutor.call(() -> {
                        log.info("m1-probe db step");
                        return Map.of(
                                "nettyThread", netty[0],
                                "nettyMdc", netty[1],
                                "dbThread", Thread.currentThread().getName(),
                                "dbMdc", String.valueOf(MDC.get(CORRELATION_ID)));
                    }));
        }

        @GetMapping("/error/api")
        Mono<Void> apiError() {
            return Mono.error(new ApiException(ErrorCode.ROOM_FULL, "The room is full."));
        }

        @GetMapping("/error/busy")
        Mono<Void> busy() {
            return Mono.error(new ApiException(ErrorCode.SERVICE_BUSY, "The service is busy. Retry shortly."));
        }

        @GetMapping("/error/unexpected")
        Mono<Void> unexpected() {
            return Mono.error(new IllegalStateException("internal detail that must not leak"));
        }

        @PostMapping("/echo")
        Mono<EchoRequest> echo(@Valid @RequestBody EchoRequest request) {
            return Mono.just(request);
        }
    }
}
