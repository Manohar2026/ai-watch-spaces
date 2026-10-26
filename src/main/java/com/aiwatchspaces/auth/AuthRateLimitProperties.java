package com.aiwatchspaces.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Auth rate limits (SYSTEM_DESIGN §8.7). */
@ConfigurationProperties("aiws.rate-limits.auth")
public record AuthRateLimitProperties(
        @DefaultValue({"5", "1m"}) Limit loginPerEmail,
        @DefaultValue({"20", "1m"}) Limit loginPerIp,
        @DefaultValue({"5", "1h"}) Limit registerPerIp) {

    public record Limit(int limit, Duration window) {
        public Limit {
            if (limit < 1 || window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("rate limit must be >= 1 per positive window");
            }
        }
    }
}
