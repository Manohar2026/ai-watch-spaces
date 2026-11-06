package com.aiwatchspaces.watchspace;

import java.util.UUID;

import reactor.core.publisher.Mono;

/** Read-only room access and connection accounting exposed to the realtime transport module. */
public interface WatchSpaceRealtimePort {
    Mono<WatchSpaceRealtimeSnapshot> snapshot(UUID watchSpaceId, UUID userId);

    void sessionOpened(UUID watchSpaceId);

    void sessionClosed(UUID watchSpaceId);

    boolean isResponsive();

    boolean hasSaturatedLane();
}
