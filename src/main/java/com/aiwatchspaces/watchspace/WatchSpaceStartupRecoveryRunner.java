package com.aiwatchspaces.watchspace;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs the M4 room recovery pass before Spring Boot marks the application ready. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class WatchSpaceStartupRecoveryRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WatchSpaceStartupRecoveryRunner.class);

    private final RoomLaneRegistry lanes;
    private final RoomRecoveryStatus recoveryStatus;
    private final Duration timeout;

    WatchSpaceStartupRecoveryRunner(
            RoomLaneRegistry lanes,
            RoomRecoveryStatus recoveryStatus,
            @Value("${aiws.watchspace.startup-recovery-timeout:2m}") Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("aiws.watchspace.startup-recovery-timeout must be positive.");
        }
        this.lanes = lanes;
        this.recoveryStatus = recoveryStatus;
        this.timeout = timeout;
    }

    @Override
    public void run(String... args) {
        Integer recovered = lanes.recoverRoomsNow().block(timeout);
        if (recovered == null) {
            throw new IllegalStateException("Room startup recovery did not return a result.");
        }
        recoveryStatus.markCompleted();
        log.info("Room startup recovery completed: recoveredRooms={}", recovered);
    }
}
