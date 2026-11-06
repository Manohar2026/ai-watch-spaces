package com.aiwatchspaces.realtime;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/** WebFlux mapping for the versioned realtime endpoint. */
@Configuration(proxyBeanMethods = false)
class RealtimeWebSocketConfiguration {

    @Bean
    HandlerMapping watchSpaceWebSocketMapping(WatchSpaceWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/watch-spaces/**", (WebSocketHandler) handler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    @ConditionalOnMissingBean(JsonMapper.class)
    JsonMapper realtimeJsonMapper() {
        return JsonMapper.builder().build();
    }
}
