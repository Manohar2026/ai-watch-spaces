package com.aiwatchspaces.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * In-memory token buckets (SYSTEM_DESIGN §8.7). Single-instance assumption: limits are per process.
 * Each bucket holds {@code limit} tokens and refills continuously over {@code window}.
 */
@Component
public class RateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Takes one token from the bucket for {@code key}.
     *
     * @return {@link Decision#allowed()} or the whole seconds to wait before retrying
     */
    public Decision tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        if (buckets.size() > CLEANUP_THRESHOLD) {
            buckets.values().removeIf(bucket -> bucket.isIdleFull(now));
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(limit, window.toMillis(), now));
        return bucket.tryAcquire(now);
    }

    /** Test support: forget all buckets. */
    public void reset() {
        buckets.clear();
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final class Bucket {
        private final double capacity;
        private final double tokensPerMilli;
        private double tokens;
        private long updatedAt;

        Bucket(int limit, long windowMillis, long now) {
            this.capacity = limit;
            this.tokensPerMilli = (double) limit / windowMillis;
            this.tokens = limit;
            this.updatedAt = now;
        }

        synchronized Decision tryAcquire(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            long waitMillis = (long) Math.ceil((1 - tokens) / tokensPerMilli);
            return new Decision(false, Math.max(1, (waitMillis + 999) / 1000));
        }

        synchronized boolean isIdleFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            if (now > updatedAt) {
                tokens = Math.min(capacity, tokens + (now - updatedAt) * tokensPerMilli);
                updatedAt = now;
            }
        }
    }
}
