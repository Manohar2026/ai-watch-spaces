package com.aiwatchspaces.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketHttpHeaders;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

/** M5 ticket-authenticated WebSocket upgrade and initial snapshot contract against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceWebSocketHandshakeIT {

    @Autowired
    WebTestClient http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    @LocalServerPort
    int port;

    private AuthTestSupport auth;
    private final ReactorNettyWebSocketClient websocket = new ReactorNettyWebSocketClient();

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(http, jdbc, encoder);
    }

    @Test
    void activeMemberGetsSingleUseTicketAndReceivesSnapshotBeforeChatHistory() {
        AuthTestSupport.Account host = auth.newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        UUID roomId = createRoom(host, titleId);

        Map<?, ?> ticketBody = issueTicket(host, roomId)
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        assertThat(ticketBody).isNotNull();
        String ticket = (String) ticketBody.get("ticket");
        assertThat(ticket).isNotBlank();
        assertThat(ticketBody.get("expiresAt")).isNotNull();

        List<String> frames = new CopyOnWriteArrayList<>();
        AtomicReference<String> selectedProtocol = new AtomicReference<>();
        connect(roomId, ticket, frames, selectedProtocol);

        assertThat(selectedProtocol.get()).isEqualTo(WebSocketTicketHandshakeAuthenticator.SELECTED_PROTOCOL);
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0))
                .contains("\"event\":\"room.snapshot\"",
                        "\"watchSpaceId\":\"" + roomId + "\"",
                        "\"status\":\"SCHEDULED\"",
                        "\"roomRole\":\"HOST\"",
                        "\"connected\":true");
        assertThat(frames.get(1))
                .contains("\"event\":\"room.chat.history\"")
                .doesNotContain("inviteCodeHash", "invite_code_hash");
    }

    @Test
    void nonMemberCannotMintAConnectionTicket() {
        AuthTestSupport.Account host = auth.newAccount();
        AuthTestSupport.Account outsider = auth.newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        UUID roomId = createRoom(host, titleId);

        http.post().uri("/api/v1/watch-spaces/" + roomId + "/connection-ticket")
                .headers(headers -> headers.setBearerAuth(outsider.tokens().accessToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void aConsumedTicketCannotOpenAnotherSocket() {
        AuthTestSupport.Account host = auth.newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        UUID roomId = createRoom(host, titleId);
        String ticket = issueTicket(host, roomId)
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody().get("ticket").toString();

        connect(roomId, ticket, new CopyOnWriteArrayList<>(), new AtomicReference<>());

        assertThatThrownBy(() -> connect(roomId, ticket, new CopyOnWriteArrayList<>(), new AtomicReference<>()))
                .isInstanceOf(RuntimeException.class);
    }

    private WebTestClient.ResponseSpec issueTicket(AuthTestSupport.Account account, UUID roomId) {
        return http.post().uri("/api/v1/watch-spaces/" + roomId + "/connection-ticket")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()))
                .exchange();
    }

    private void connect(UUID roomId, String ticket, List<String> frames,
                         AtomicReference<String> selectedProtocol) {
        URI uri = URI.create("ws://localhost:" + port + "/ws/watch-spaces/" + roomId);
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of(
                WebSocketTicketHandshakeAuthenticator.SELECTED_PROTOCOL,
                WebSocketTicketHandshakeAuthenticator.TICKET_PROTOCOL_PREFIX + ticket));

        Mono<Void> connection = websocket.execute(uri, headers, session -> {
            selectedProtocol.set(session.getHandshakeInfo().getSubProtocol());
            return session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .take(2)
                    .doOnNext(frames::add)
                    .then(session.close());
        });
        connection.block(Duration.ofSeconds(10));
    }

    private UUID createPublishedTitle(UUID createdBy) {
        UUID titleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO titles
                    (id, slug, name, description, duration_ms, video_asset_uri,
                     default_locale, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'en', 'PUBLISHED', ?)
                """, titleId, "m5-room-" + titleId, "M5 Test Title",
                "A private fixture for WebSocket handshake tests.", 60_000,
                "asset://m5-room-test/video.mp4", createdBy);
        return titleId;
    }

    private UUID createRoom(AuthTestSupport.Account host, UUID titleId) {
        Map<?, ?> response = http.post().uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("titleId", titleId.toString(), "name", "M5 Handshake Test"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        return UUID.fromString(response.get("watchSpaceId").toString());
    }
}
