package com.aiwatchspaces.watchspace;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Watch-space REST rate limits (SYSTEM_DESIGN §8.7). */
@Component
@ConfigurationProperties("aiws.rate-limits.watchspace")
public record WatchSpaceRateLimitProperties(
        @DefaultValue({"10", "1m"}) Limit joinPerUser,
        @DefaultValue({"30", "1m"}) Limit joinPerIp,
        @DefaultValue({"5", "1h"}) Limit inviteCodeRotationPerRoom) {

    public record Limit(int limit, Duration window) {
        public Limit {
            if (limit < 1 || window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("rate limit must be >= 1 per positive window");
            }
        }
    }
}
