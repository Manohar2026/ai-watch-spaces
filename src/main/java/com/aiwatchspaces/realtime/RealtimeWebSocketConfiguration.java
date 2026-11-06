package com.aiwatchspaces.realtime;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

/** WebFlux mapping for the versioned realtime endpoint. */
@Configuration(proxyBeanMethods = false)
class RealtimeWebSocketConfiguration {

    @Bean
    HandlerMapping watchSpaceWebSocketMapping(WatchSpaceWebSocketHandler handler,
                                               WebSocketTransportStatus transportStatus) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/watch-spaces/**", (WebSocketHandler) handler));
        mapping.setOrder(-1);
        transportStatus.markHandlerMappingRegistered();
        return mapping;
    }
}
