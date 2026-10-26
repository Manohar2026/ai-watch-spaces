package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** SYSTEM_DESIGN §6.1: error envelope, codes, X-Correlation-Id handling, Retry-After. */
@IntegrationTest
class ErrorEnvelopeIT {

    private static final String HEADER = "X-Correlation-Id";

    @Autowired
    WebTestClient client;

    @Test
    void validCorrelationIdIsAcceptedAndEchoedInHeaderAndEnvelope() {
        String id = UUID.randomUUID().toString();
        client.get().uri("/m1-probe/error/api").header(HEADER, id.toUpperCase()).exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().valueEquals(HEADER, id)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("ROOM_FULL")
                .jsonPath("$.message").isEqualTo("The room is full.")
                .jsonPath("$.correlationId").isEqualTo(id)
                .jsonPath("$.details").isArray()
                .jsonPath("$.details.length()").isEqualTo(0);
    }

    @Test
    void invalidCorrelationIdIsReplacedWithAGeneratedUuid() {
        String echoed = client.get().uri("/m1-probe/error/api").header(HEADER, "not-a-uuid").exchange()
                .expectStatus().isEqualTo(409)
                .returnResult(String.class).getResponseHeaders().getFirst(HEADER);
        assertThat(echoed).isNotEqualTo("not-a-uuid");
        assertThat(UUID.fromString(echoed).toString()).isEqualTo(echoed);
    }

    @Test
    void correlationIdIsGeneratedWhenAbsentAndOnSuccessfulResponses() {
        String echoed = client.get().uri("/actuator/health/liveness").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst(HEADER);
        assertThat(UUID.fromString(echoed)).isNotNull();
    }

    @Test
    void unauthenticatedApiRequestReturns401Envelope() {
        String id = UUID.randomUUID().toString();
        client.get().uri("/api/v1/me").header(HEADER, id).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HEADER, id)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.correlationId").isEqualTo(id);
    }

    @Test
    void unexpectedErrorReturns500WithoutInternalDetails() {
        client.get().uri("/m1-probe/error/unexpected").exchange()
                .expectStatus().isEqualTo(500)
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("\"code\":\"INTERNAL_ERROR\"");
                    assertThat(body).doesNotContain("internal detail", "IllegalStateException", "at com.", "trace");
                });
    }

    @Test
    void serviceBusyCarriesRetryAfter() {
        client.get().uri("/m1-probe/error/busy").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_BUSY");
    }

    @Test
    void beanValidationFailureReturnsFieldDetails() {
        client.post().uri("/m1-probe/echo").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\" \",\"tag\":\"toolong\"}").exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.details.length()").isEqualTo(2)
                .jsonPath("$.details[?(@.field == 'name')].code").isEqualTo("NOT_BLANK")
                .jsonPath("$.details[?(@.field == 'tag')].code").isEqualTo("SIZE");
    }

    @Test
    void unknownJsonPropertyIsRejectedAsMalformed() {
        client.post().uri("/m1-probe/echo").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"ok\",\"unexpected\":1}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void invalidJsonIsRejectedAsMalformed() {
        client.post().uri("/m1-probe/echo").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void validRequestSucceeds() {
        client.post().uri("/m1-probe/echo").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"ok\",\"tag\":\"t\"}").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("ok");
    }

    @Test
    void unknownProbeRouteReturns404Envelope() {
        client.get().uri("/m1-probe/does-not-exist").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }
}
