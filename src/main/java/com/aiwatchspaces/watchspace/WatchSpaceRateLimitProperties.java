package com.aiwatchspaces.watchspace;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Watch-space REST rate limits (SYSTEM_DESIGN §8.7). */
@Component
final class WatchSpaceRateLimitProperties {

    private final Limit joinPerUser;
    private final Limit joinPerIp;
    private final Limit inviteCodeRotationPerRoom;

    WatchSpaceRateLimitProperties(
            @Value("${aiws.rate-limits.watchspace.join-per-user.limit:10}") int joinPerUserLimit,
            @Value("${aiws.rate-limits.watchspace.join-per-user.window:1m}") Duration joinPerUserWindow,
            @Value("${aiws.rate-limits.watchspace.join-per-ip.limit:30}") int joinPerIpLimit,
            @Value("${aiws.rate-limits.watchspace.join-per-ip.window:1m}") Duration joinPerIpWindow,
            @Value("${aiws.rate-limits.watchspace.invite-code-rotation-per-room.limit:5}") int rotationLimit,
            @Value("${aiws.rate-limits.watchspace.invite-code-rotation-per-room.window:1h}") Duration rotationWindow) {
        this.joinPerUser = new Limit(joinPerUserLimit, joinPerUserWindow);
        this.joinPerIp = new Limit(joinPerIpLimit, joinPerIpWindow);
        this.inviteCodeRotationPerRoom = new Limit(rotationLimit, rotationWindow);
    }

    Limit joinPerUser() {
        return joinPerUser;
    }

    Limit joinPerIp() {
        return joinPerIp;
    }

    Limit inviteCodeRotationPerRoom() {
        return inviteCodeRotationPerRoom;
    }

    record Limit(int limit, Duration window) {
        Limit {
            if (limit < 1 || window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("rate limit must be >= 1 per positive window");
            }
        }
    }
}
