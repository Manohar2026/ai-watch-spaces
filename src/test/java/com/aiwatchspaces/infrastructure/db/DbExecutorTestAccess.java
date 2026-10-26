package com.aiwatchspaces.infrastructure.db;

/** Lets tests in other packages build a {@link DbExecutor} without Spring. */
public final class DbExecutorTestAccess {

    private DbExecutorTestAccess() {
    }

    public static DbExecutor create(int threads, int queueCapacity) {
        return new DbExecutor(threads, queueCapacity);
    }
}
