package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M1-V10 (Reactor -> MDC propagation across Netty -> db-* hops) and M1-V13 (structured JSON
 * console logging carrying correlationId).
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingAndMdcIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void correlationIdReachesMdcOnNettyAndDbThreadsAndAppearsInJsonLogs(CapturedOutput output) {
        String id = UUID.randomUUID().toString();

        client.get().uri("/m1-probe/mdc").header("X-Correlation-Id", id).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.nettyThread").value(t -> assertThat((String) t).startsWith(BlockingBoundaryIT.NETTY_EVENT_LOOP))
                .jsonPath("$.nettyMdc").isEqualTo(id)
                .jsonPath("$.dbThread").value(t -> assertThat((String) t).startsWith("db-"))
                .jsonPath("$.dbMdc").isEqualTo(id);

        JsonNode nettyLine = findLogLine(output, "m1-probe netty step", id);
        JsonNode dbLine = findLogLine(output, "m1-probe db step", id);
        assertThat(nettyLine.at("/process/thread/name").asString()).startsWith(BlockingBoundaryIT.NETTY_EVENT_LOOP);
        assertThat(dbLine.at("/process/thread/name").asString()).startsWith("db-");
        assertThat(dbLine.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(dbLine.at("/service/name").asString()).isEqualTo("ai-watch-spaces");
    }

    @Test
    void mdcDoesNotLeakIntoTheNextTaskOnTheSameDbThread() {
        String first = UUID.randomUUID().toString();
        client.get().uri("/m1-probe/mdc").header("X-Correlation-Id", first).exchange().expectStatus().isOk();
        // Many follow-up requests without a supplied id: each must see only its own generated id.
        for (int i = 0; i < 25; i++) {
            var result = client.get().uri("/m1-probe/mdc").exchange()
                    .expectStatus().isOk().returnResult(JsonNode.class);
            String echoed = result.getResponseHeaders().getFirst("X-Correlation-Id");
            JsonNode body = result.getResponseBody().blockFirst();
            assertThat(body.get("dbMdc").asString()).isEqualTo(echoed).isNotEqualTo(first);
            assertThat(body.get("nettyMdc").asString()).isEqualTo(echoed);
        }
    }

    private JsonNode findLogLine(CapturedOutput output, String message, String correlationId) {
        List<JsonNode> matches = output.getOut().lines()
                .filter(line -> line.startsWith("{") && line.contains(message))
                .map(jsonMapper::readTree)
                .filter(node -> correlationId.equals(node.path("correlationId").asString(null)))
                .toList();
        assertThat(matches).as("JSON log line '%s' with correlationId", message).hasSize(1);
        return matches.getFirst();
    }
}
