package com.aiwatchspaces.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import reactor.core.publisher.Flux;

/** M4 join policy, idempotence, quick-rejoin and capacity serialization against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceJoinIT {

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
    void inviteCodeJoinIsIdempotentAndRoomIdJoinReturnsExistingMembership() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account guest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> room = createRoom(host, titleId, Map.of());
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));
        String inviteCode = (String) room.get("inviteCode");

        Map<?, ?> first = joinByCode(guest, inviteCode);
        assertThat(first.get("watchSpaceId")).isEqualTo(roomId.toString());
        assertThat(first.get("userId")).isEqualTo(guest.userId().toString());
        assertThat(first.get("roomRole")).isEqualTo("VIEWER");
        assertThat(first.get("alreadyMember")).isEqualTo(false);
        assertThat(first.get("rejoined")).isEqualTo(false);

        UUID membershipId = UUID.fromString((String) first.get("membershipId"));
        Map<?, ?> second = joinById(guest, roomId, null);
        assertThat(second.get("membershipId")).isEqualTo(membershipId.toString());
        assertThat(second.get("alreadyMember")).isEqualTo(true);
        assertThat(second.get("rejoined")).isEqualTo(false);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM room_events WHERE watch_space_id = ?", Integer.class, roomId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND left_at IS NULL
                """, Integer.class, roomId)).isEqualTo(2);
    }

    @Test
    void publicLinkCanBeJoinedByRoomIdWithoutAnInviteCode() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account guest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> room = createRoom(host, titleId, Map.of("privacy", "PUBLIC_LINK"));
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));

        Map<?, ?> joined = joinById(guest, roomId, null);
        assertThat(joined.get("watchSpaceId")).isEqualTo(roomId.toString());
        assertThat(joined.get("alreadyMember")).isEqualTo(false);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND user_id = ? AND left_at IS NULL
                """, Integer.class, roomId, guest.userId())).isEqualTo(1);
    }

    @Test
    void quickRejoinBypassesLockButNewUsersAreBlockedAndRemovedUsersStayBlocked() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account formerMember = newAccount();
        AuthTestSupport.Account newGuest = newAccount();
        AuthTestSupport.Account removedUser = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> room = createRoom(host, titleId, Map.of());
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));
        String inviteCode = (String) room.get("inviteCode");

        Map<?, ?> initialJoin = joinById(formerMember, roomId, inviteCode);
        UUID oldMembershipId = UUID.fromString((String) initialJoin.get("membershipId"));
        jdbc.update("""
                UPDATE watch_space_participants
                SET left_at = now(), leave_reason = 'LEFT'
                WHERE id = ?
                """, oldMembershipId);
        jdbc.update("UPDATE watch_spaces SET is_locked = true WHERE id = ?", roomId);

        Map<?, ?> rejoined = joinById(formerMember, roomId, null);
        assertThat(rejoined.get("rejoined")).isEqualTo(true);
        assertThat(rejoined.get("alreadyMember")).isEqualTo(false);
        assertThat(rejoined.get("membershipId")).isNotEqualTo(oldMembershipId.toString());

        joinByIdExpectCode(newGuest, roomId, inviteCode, "ROOM_LOCKED");

        jdbc.update("""
                INSERT INTO watch_space_moderation
                    (watch_space_id, user_id, removed_at, removed_by, updated_at)
                VALUES (?, ?, now(), ?, now())
                """, roomId, removedUser.userId(), host.userId());
        joinByIdExpectCode(removedUser, roomId, inviteCode, "REMOVED_FROM_ROOM");
    }

    @Test
    void concurrentJoinsNeverExceedRoomCapacity() {
        AuthTestSupport.Account host = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> room = createRoom(host, titleId, Map.of("maxParticipants", 5));
        UUID roomId = UUID.fromString((String) room.get("watchSpaceId"));
        String inviteCode = (String) room.get("inviteCode");

        List<AuthTestSupport.Account> guests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            guests.add(newAccount());
        }
        rateLimiter.reset();

        List<JoinAttempt> attempts = Flux.fromIterable(guests)
                .parallel(20)
                .runOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(guest -> {
                    var result = client.post().uri("/api/v1/watch-spaces/" + roomId + "/join")
                            .headers(headers -> headers.setBearerAuth(guest.tokens().accessToken()))
                            .bodyValue(Map.of("inviteCode", inviteCode))
                            .exchange()
                            .expectBody(String.class)
                            .returnResult();
                    String body = result.getResponseBody() == null ? "" : result.getResponseBody();
                    return new JoinAttempt(result.getStatus().value(), body);
                })
                .sequential()
                .collectList()
                .block(Duration.ofSeconds(45));

        assertThat(attempts).hasSize(20);
        assertThat(attempts.stream().filter(attempt -> attempt.status() == 200)).hasSize(4);
        assertThat(attempts.stream().filter(attempt -> attempt.status() == 409)
                .map(JoinAttempt::body)).allMatch(body -> body.contains("ROOM_FULL"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND left_at IS NULL
                """, Integer.class, roomId)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId))
                .isEqualTo(6L);
        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId))
                .isEqualTo(4L);
    }

    private AuthTestSupport.Account newAccount() {
        // Keep this test independent of the auth fixture's deliberately strict per-IP registration limits.
        rateLimiter.reset();
        return auth.newAccount();
    }

    private UUID createPublishedTitle(UUID createdBy) {
        UUID titleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO titles
                    (id, slug, name, description, duration_ms, video_asset_uri, default_locale, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'en', 'PUBLISHED', ?)
                """, titleId, "m4-join-" + titleId, "M4 Join Test Title",
                "A private fixture for watch-space join tests.", 60_000,
                "asset://m4-join-test/video.mp4", createdBy);
        return titleId;
    }

    private Map<?, ?> createRoom(
            AuthTestSupport.Account host, UUID titleId, Map<String, Object> additionalFields) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("titleId", titleId.toString());
        body.putAll(additionalFields);
        return client.post().uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }

    private Map<?, ?> joinByCode(AuthTestSupport.Account account, String inviteCode) {
        return client.post().uri("/api/v1/watch-spaces/join")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()))
                .bodyValue(Map.of("inviteCode", inviteCode))
                .exchange()
                .expectStatus().isOk()
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

    private void joinByIdExpectCode(
            AuthTestSupport.Account account, UUID roomId, String inviteCode, String expectedCode) {
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/join")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()))
                .bodyValue(Map.of("inviteCode", inviteCode))
                .exchange()
                .expectStatus().isEqualTo(expectedCode.equals("REMOVED_FROM_ROOM") ? 403 : 409)
                .expectBody().jsonPath("$.code").isEqualTo(expectedCode);
    }

    private record JoinAttempt(int status, String body) {
    }
}
