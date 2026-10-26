package com.aiwatchspaces.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    };

    @Test
    void allowsTheLimitThenRefillsContinuously() {
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1)).allowed()).isTrue();
        }
        RateLimiter.Decision denied = limiter.tryAcquire("k", 5, Duration.ofMinutes(1));
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(12);
        assertThat(limiter.tryAcquire("other", 5, Duration.ofMinutes(1)).allowed()).isTrue();

        millis.addAndGet(12_000);
        assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1)).allowed()).isFalse();
    }
}
