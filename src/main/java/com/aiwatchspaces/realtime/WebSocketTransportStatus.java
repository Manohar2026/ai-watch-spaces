package com.aiwatchspaces.realtime;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/** Tracks whether the WebFlux WebSocket handler mapping was registered in this application context. */
@Component
final class WebSocketTransportStatus {

    private final AtomicBoolean handlerMappingRegistered = new AtomicBoolean();

    void markHandlerMappingRegistered() {
        handlerMappingRegistered.set(true);
    }

    boolean isHandlerMappingRegistered() {
        return handlerMappingRegistered.get();
    }
}
