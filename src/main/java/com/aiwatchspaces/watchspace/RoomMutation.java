package com.aiwatchspaces.watchspace;

import java.time.Instant;

/**
 * One durable command executed by a room's serialized lane. Implementations validate and decide
 * against the current immutable state, then call a transactional persistence service.
 */
@FunctionalInterface
interface RoomMutation<R> {
    RoomMutationCommit<R> apply(RoomState current, Instant mutationInstant);

    /** Creation is the only mutation allowed to run before a room row exists. */
    default boolean createsRoom() {
        return false;
    }
}
