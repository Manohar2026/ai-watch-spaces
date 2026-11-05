package com.aiwatchspaces.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.ratelimit.RateLimiter;
import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** M4 persistent mute and removal moderation behavior against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceModerationIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    RateLimiter rateLimiter;

    private AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void muteSurvivesLeaveAndRejoinAndCanBeLiftedByHost() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account guest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> room = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));
        String inviteCode = (String) room.get("inviteCode");
        joinById(guest, roomId, inviteCode);

        mute(host, roomId, guest.userId(), true)
                .expectStatus().isOk();
        assertMutedInParticipants(guest, roomId, guest.userId(), true);

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/leave")
                .headers(headers -> headers.setBearerAuth(guest.tokens().accessToken()))
                .exchange()
                .expectStatus().isNoContent();

        // Mute state is keyed by room/user, not by a single membership interval.
        joinById(guest, roomId, null);
        assertMutedInParticipants(guest, roomId, guest.userId(), true);

        mute(host, roomId, guest.userId(), false)
                .expectStatus().isOk();
        assertMutedInParticipants(guest, roomId, guest.userId(), false);

        assertThat(jdbc.queryForObject("""
                SELECT is_muted FROM watch_space_moderation
                WHERE watch_space_id = ? AND user_id = ?
                """, Boolean.class, roomId, guest.userId())).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'PARTICIPANT_MUTE_CHANGED'
                """, Integer.class, roomId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND user_id = ?
                """, Integer.class, roomId, guest.userId())).isEqualTo(2);
    }

    @Test
    void removalClosesMembershipAndMakesRejoinPermanentlyFail() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account guest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> room = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));
        String inviteCode = (String) room.get("inviteCode");
        Map<?, ?> joined = joinById(guest, roomId, inviteCode);
        UUID membershipId = UUID.fromString((String) joined.get("membershipId"));

        client.delete().uri("/api/v1/watch-spaces/" + roomId + "/participants/" + guest.userId())
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isNoContent();

        assertThat(jdbc.queryForObject("""
                SELECT leave_reason FROM watch_space_participants WHERE id = ?
                """, String.class, membershipId)).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND user_id = ? AND left_at IS NULL
                """, Integer.class, roomId, guest.userId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT removed_by FROM watch_space_moderation
                WHERE watch_space_id = ? AND user_id = ?
                """, UUID.class, roomId, guest.userId())).isEqualTo(host.userId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'PARTICIPANT_REMOVED'
                """, Integer.class, roomId)).isEqualTo(1);

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/join")
                .headers(headers -> headers.setBearerAuth(guest.tokens().accessToken()))
                .bodyValue(Map.of("inviteCode", inviteCode))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("REMOVED_FROM_ROOM");

        // Repeating removal acknowledges the already-removed state without a duplicate event.
        client.delete().uri("/api/v1/watch-spaces/" + roomId + "/participants/" + guest.userId())
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isNoContent();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'PARTICIPANT_REMOVED'
                """, Integer.class, roomId)).isEqualTo(1);
    }

    @Test
    void hostCannotMuteOrRemoveThemselfAndOrdinaryMembersCannotModerate() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account guest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> room = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));
        joinById(guest, roomId, (String) room.get("inviteCode"));

        mute(host, roomId, host.userId(), true)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");

        client.delete().uri("/api/v1/watch-spaces/" + roomId + "/participants/" + host.userId())
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");

        mute(guest, roomId, host.userId(), true)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_ROOM_HOST");

        client.delete().uri("/api/v1/watch-spaces/" + roomId + "/participants/" + host.userId())
                .headers(headers -> headers.setBearerAuth(guest.tokens().accessToken()))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type IN ('PARTICIPANT_MUTE_CHANGED', 'PARTICIPANT_REMOVED')
                """, Integer.class, roomId)).isZero();
    }

    private AuthTestSupport.Account newAccount() {
        rateLimiter.reset();
        return auth.newAccount();
    }

    private UUID createPublishedTitle(UUID createdBy) {
        UUID titleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO titles
                    (id, slug, name, description, duration_ms, video_asset_uri, default_locale, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'en', 'PUBLISHED', ?)
                """, titleId, "m4-moderation-" + titleId, "M4 Moderation Test Title",
                "A private fixture for watch-space moderation tests.", 60_000,
                "asset://m4-moderation-test/video.mp4", createdBy);
        return titleId;
    }

    private Map<?, ?> createRoom(AuthTestSupport.Account host, UUID titleId) {
        return client.post().uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("titleId", titleId.toString()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }

    private Map<?, ?> joinById(AuthTestSupport.Account account, UUID roomId, String inviteCode) {
        var request = client.post().uri("/api/v1/watch-spaces/" + roomId + "/join")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()));
        if (inviteCode != null) {
            return request.bodyValue(Map.of("inviteCode", inviteCode))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Map.class)
                    .returnResult().getResponseBody();
        }
        return request.exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec mute(
            AuthTestSupport.Account actor, UUID roomId, UUID targetUserId, boolean muted) {
        return client.post().uri("/api/v1/watch-spaces/" + roomId + "/participants/" + targetUserId + "/mute")
                .headers(headers -> headers.setBearerAuth(actor.tokens().accessToken()))
                .bodyValue(Map.of("muted", muted))
                .exchange();
    }

    private void assertMutedInParticipants(
            AuthTestSupport.Account reader, UUID roomId, UUID targetUserId, boolean expectedMuted) {
        client.get().uri("/api/v1/watch-spaces/" + roomId + "/participants")
                .headers(headers -> headers.setBearerAuth(reader.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.userId == '" + targetUserId + "')].muted")
                .isEqualTo(java.util.List.of(expectedMuted));
    }
}
