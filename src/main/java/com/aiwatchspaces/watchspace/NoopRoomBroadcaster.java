package com.aiwatchspaces.watchspace;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** M4 adapter: REST lifecycle and ledger work precede the M5 WebSocket transport. */
@Component
class NoopRoomBroadcaster implements RoomBroadcaster {
    @Override
    public void broadcast(UUID watchSpaceId, List<RoomEvent> committedEvents) {
        // No connected WebSocket transport exists before M5.
    }
}
