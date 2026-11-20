package com.aiwatchspaces.m4;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Regression coverage for room membership authorization and the leave lifecycle. */
@IntegrationTest
class WatchSpaceMembershipIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    private AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void leavingRoomRevokesRoomReadAccessAndRemovesParticipantFromActiveRoster() {
        var host = auth.newAccount();
        var viewer = auth.newAccount();
        UUID titleId = createPublishedTitle(host.userId());

        Map<?, ?> created = client.post().uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("titleId", titleId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(created).isNotNull();
        Map<?, ?> room = (Map<?, ?>) created.get("room");
        assertThat(room).isNotNull();
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));
        String inviteCode = (String) created.get("inviteCode");
        assertThat(inviteCode).isNotBlank();

        client.post().uri("/api/v1/watch-spaces/join")
                .headers(headers -> headers.setBearerAuth(viewer.tokens().accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("inviteCode", inviteCode))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.myRole").isEqualTo("VIEWER")
                .jsonPath("$.participantCount").isEqualTo(2);

        Map<?, ?> sentMessage = client.post()
                .uri("/api/v1/watch-spaces/{id}/chat", roomId)
                .headers(headers -> headers.setBearerAuth(viewer.tokens().accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("body", "Hello from the viewer."))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(sentMessage).isNotNull();
        assertThat(sentMessage.get("body")).isEqualTo("Hello from the viewer.");

        client.get().uri("/api/v1/watch-spaces/{id}/chat?afterSequence=0&limit=50", roomId)
                .headers(headers -> headers.setBearerAuth(viewer.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].body").isEqualTo("Hello from the viewer.");

        Map<?, ?> afterLeave = client.post()
                .uri("/api/v1/watch-spaces/{id}/leave", roomId)
                .headers(headers -> headers.setBearerAuth(viewer.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(afterLeave).isNotNull();
        assertThat(afterLeave.get("myRole")).isEqualTo("NONE");
        Integer activeMemberships = jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND user_id = ? AND left_at IS NULL
                """, Integer.class, roomId, viewer.userId());
        assertThat(activeMemberships).isZero();

        client.get().uri("/api/v1/watch-spaces/{id}", roomId)
                .headers(headers -> headers.setBearerAuth(viewer.tokens().accessToken()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_ROOM_MEMBER");

        client.get().uri("/api/v1/watch-spaces/{id}/chat?afterSequence=0&limit=50", roomId)
                .headers(headers -> headers.setBearerAuth(viewer.tokens().accessToken()))
                .exchange()
                .expectStatus().isForbidden();

        client.get().uri("/api/v1/watch-spaces/{id}/chat?afterSequence=0&limit=50", roomId)
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].body").isEqualTo("Hello from the viewer.");

        client.get().uri("/api/v1/watch-spaces/{id}", roomId)
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.myRole").isEqualTo("HOST");
    }

    private UUID createPublishedTitle(UUID createdBy) {
        UUID titleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO titles (
                    id, slug, name, description, duration_ms, video_asset_uri, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'PUBLISHED', ?)
                """, titleId, "membership-test-" + titleId, "Membership Test Title",
                "Test title for watch-space membership authorization.", 120_000,
                "https://example.test/watch-spaces/sample.mp4", createdBy);
        return titleId;
    }
}
