package com.aiwatchspaces.watchspace;

import java.util.List;
import java.util.UUID;

/** Watchspace-owned transport port; the realtime module supplies the WebSocket implementation in M5. */
public interface RoomBroadcaster {
    void broadcast(UUID watchSpaceId, List<RoomEvent> committedEvents);
}
