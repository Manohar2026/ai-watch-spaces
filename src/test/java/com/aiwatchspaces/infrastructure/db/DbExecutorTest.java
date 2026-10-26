package com.aiwatchspaces.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class DbExecutorTest {

    @Test
    void runsWorkOnNamedDbThreads() throws Exception {
        try (DbExecutor executor = new DbExecutor(2, 10)) {
            StepVerifier.create(executor.call(() -> Thread.currentThread().getName()))
                    .assertNext(name -> assertThat(name).matches("db-\\d+"))
                    .verifyComplete();
            assertThat(executor.threadCount()).isEqualTo(2);
        }
    }

    @Test
    void nullResultCompletesEmpty() throws Exception {
        try (DbExecutor executor = new DbExecutor(1, 1)) {
            StepVerifier.create(executor.call(() -> null)).verifyComplete();
        }
    }

    @Test
    void fullQueueIsRejectedWithServiceBusy() throws Exception {
        try (DbExecutor executor = new DbExecutor(1, 1)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.call(() -> {
                running.countDown();
                return release.await(10, TimeUnit.SECONDS);
            }).subscribe();
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            executor.call(() -> "queued").subscribe(); // fills the single queue slot

            StepVerifier.create(executor.call(() -> "rejected"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ApiException.class);
                        assertThat(((ApiException) error).code()).isEqualTo(ErrorCode.SERVICE_BUSY);
                    })
                    .verify();
            release.countDown();
        }
    }

    @Test
    void restoresReactorContextIntoMdcOnDbThreadAndClearsItAfterwards() throws Exception {
        // Registers the MDC accessors (normally done when the Spring configuration class loads).
        Class.forName("com.aiwatchspaces.infrastructure.logging.MdcContextPropagation");
        try (DbExecutor executor = new DbExecutor(1, 10)) {
            Mono<String> withContext = executor.call(() -> MDC.get("correlationId"))
                    .contextWrite(Context.of("correlationId", "abc"));
            StepVerifier.create(withContext).expectNext("abc").verifyComplete();
            // Same single thread, no context: nothing left over from the previous task.
            StepVerifier.create(executor.call(() -> String.valueOf(MDC.get("correlationId"))))
                    .expectNext("null").verifyComplete();
        }
    }

    @Test
    void threadCountMustEqualHikariPoolSize() {
        DbExecutorConfig.requireMatchingPoolSize(10, 10);
        assertThatThrownBy(() -> DbExecutorConfig.requireMatchingPoolSize(8, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must equal spring.datasource.hikari.maximum-pool-size");
    }
}
