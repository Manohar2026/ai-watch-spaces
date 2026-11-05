package com.aiwatchspaces.watchspace;

import java.util.List;

/** Value returned only after the persistence transaction has committed. */
record RoomMutationCommit<R>(RoomState nextState, R response, List<RoomEvent> events) {
    RoomMutationCommit {
        if (nextState == null) {
            throw new IllegalArgumentException("A committed room mutation must publish a state.");
        }
        events = List.copyOf(events);
    }
}
