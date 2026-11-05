package com.aiwatchspaces.watchspace;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/** Readiness gate for the M4 startup room-recovery pass. */
@Component
public final class RoomRecoveryStatus {

    private final AtomicBoolean completed = new AtomicBoolean();

    public boolean isCompleted() {
        return completed.get();
    }

    public void markCompleted() {
        completed.set(true);
    }
}
