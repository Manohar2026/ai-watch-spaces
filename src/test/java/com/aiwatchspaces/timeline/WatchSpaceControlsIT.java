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

/** M4 room lock, host settings and invite rotation against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceControlsIT {

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
    void lockBlocksNewMembersButAllowsQuickRejoinAndIsIdempotent() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account returningGuest = newAccount();
        AuthTestSupport.Account newGuest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> created = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) created.get("watchSpaceId"));
        String inviteCode = (String) created.get("inviteCode");

        Map<?, ?> firstJoin = joinById(returningGuest, roomId, inviteCode);
        UUID firstMembershipId = UUID.fromString((String) firstJoin.get("membershipId"));
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/leave")
                .headers(headers -> headers.setBearerAuth(returningGuest.tokens().accessToken()))
                .exchange()
                .expectStatus().isNoContent();

        Map<?, ?> locked = client.post().uri("/api/v1/watch-spaces/" + roomId + "/lock")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("locked", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(locked).isNotNull();
        assertThat(locked.get("locked")).isEqualTo(true);

        long versionAfterLock = jdbc.queryForObject(
                "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId);
        long sequenceAfterLock = jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_LOCK_CHANGED'
                """, Integer.class, roomId)).isEqualTo(1);

        // Repeating the same lock command is a no-op: no new event or version.
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/lock")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("locked", true))
                .exchange()
                .expectStatus().isOk();
        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId))
                .isEqualTo(versionAfterLock);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId))
                .isEqualTo(sequenceAfterLock);

        joinByIdExpectCode(newGuest, roomId, inviteCode, "ROOM_LOCKED");

        Map<?, ?> rejoined = joinById(returningGuest, roomId, null);
        assertThat(rejoined.get("rejoined")).isEqualTo(true);
        assertThat(rejoined.get("alreadyMember")).isEqualTo(false);
        assertThat(rejoined.get("membershipId")).isNotEqualTo(firstMembershipId.toString());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND user_id = ? AND left_at IS NULL
                """, Integer.class, roomId, returningGuest.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId))
                .isEqualTo(sequenceAfterLock + 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_LOCK_CHANGED'
                """, Integer.class, roomId)).isEqualTo(1);

        Map<?, ?> unlocked = client.post().uri("/api/v1/watch-spaces/" + roomId + "/lock")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("locked", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(unlocked).isNotNull();
        assertThat(unlocked.get("locked")).isEqualTo(false);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_LOCK_CHANGED'
                """, Integer.class, roomId)).isEqualTo(2);
    }

    @Test
    void settingsPatchChangesOnlySuppliedFieldsAndRejectsCapacityBelowActiveMemberships() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account firstGuest = newAccount();
        AuthTestSupport.Account secondGuest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> created = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) created.get("watchSpaceId"));
        String inviteCode = (String) created.get("inviteCode");
        joinById(firstGuest, roomId, inviteCode);
        joinById(secondGuest, roomId, inviteCode);

        Map<?, ?> settings = client.patch().uri("/api/v1/watch-spaces/" + roomId + "/settings")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of(
                        "triviaEnabled", false,
                        "aiVerbosity", "CHATTY",
                        "chatMuted", true,
                        "maxParticipants", 5))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        assertThat(settings).isNotNull();
        assertThat(settings.get("triviaEnabled")).isEqualTo(false);
        assertThat(settings.get("votingEnabled")).isEqualTo(true);
        assertThat(settings.get("aiVerbosity")).isEqualTo("CHATTY");
        assertThat(settings.get("chatMuted")).isEqualTo(true);
        assertThat(settings.get("maxParticipants")).isEqualTo(5);

        // A partial patch must not reset fields omitted from the request.
        client.patch().uri("/api/v1/watch-spaces/" + roomId + "/settings")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("votingEnabled", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.triviaEnabled").isEqualTo(false)
                .jsonPath("$.votingEnabled").isEqualTo(false)
                .jsonPath("$.aiVerbosity").isEqualTo("CHATTY")
                .jsonPath("$.chatMuted").isEqualTo(true)
                .jsonPath("$.maxParticipants").isEqualTo(5);

        // Three active members (host + two guests): reducing capacity below three is rejected.
        client.patch().uri("/api/v1/watch-spaces/" + roomId + "/settings")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("maxParticipants", 2))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ROOM_FULL");

        client.patch().uri("/api/v1/watch-spaces/" + roomId + "/settings")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_SETTINGS_UPDATED'
                """, Integer.class, roomId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND left_at IS NULL
                """, Integer.class, roomId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT max_participants FROM watch_spaces WHERE id = ?", Integer.class, roomId)).isEqualTo(5);
    }

    @Test
    void inviteRotationInvalidatesOldCodeStoresOnlyTheDigestAndIsRateLimited() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account oldCodeGuest = newAccount();
        AuthTestSupport.Account newCodeGuest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> created = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) created.get("watchSpaceId"));
        String originalCode = (String) created.get("inviteCode");
        String originalHash = jdbc.queryForObject(
                "SELECT invite_code_hash FROM watch_spaces WHERE id = ?", String.class, roomId);

        String latestCode = null;
        for (int i = 0; i < 5; i++) {
            Map<?, ?> rotated = client.post().uri("/api/v1/watch-spaces/" + roomId + "/invite-code")
                    .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(Map.class)
                    .returnResult().getResponseBody();
            assertThat(rotated).isNotNull();
            latestCode = (String) rotated.get("inviteCode");
            assertThat(latestCode).matches("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}");
            assertThat(latestCode).isNotEqualTo(originalCode);
        }

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/invite-code")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");

        String rotatedHash = jdbc.queryForObject(
                "SELECT invite_code_hash FROM watch_spaces WHERE id = ?", String.class, roomId);
        assertThat(rotatedHash).hasSize(64).isNotEqualTo(originalCode).isNotEqualTo(latestCode);
        assertThat(rotatedHash).isNotEqualTo(originalHash);

        client.post().uri("/api/v1/watch-spaces/join")
                .headers(headers -> headers.setBearerAuth(oldCodeGuest.tokens().accessToken()))
                .bodyValue(Map.of("inviteCode", originalCode))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("INVITE_CODE_INVALID");

        joinByInvite(newCodeGuest, latestCode);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'INVITE_CODE_ROTATED'
                """, Integer.class, roomId)).isEqualTo(5);
        String eventPayloads = jdbc.queryForObject("""
                SELECT string_agg(payload::text, ' ')
                FROM room_events
                WHERE watch_space_id = ? AND event_type = 'INVITE_CODE_ROTATED'
                """, String.class, roomId);
        assertThat(eventPayloads).doesNotContain(originalCode).doesNotContain(latestCode);
    }

    @Test
    void onlyTheHostCanChangeLockSettingsOrRotateInvites() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account guest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> created = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) created.get("watchSpaceId"));
        joinById(guest, roomId, (String) created.get("inviteCode"));

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/lock")
                .headers(headers -> headers.setBearerAuth(guest.tokens().accessToken()))
                .bodyValue(Map.of("locked", true))
                .exchange()
                .expectStatus().isForbidden();

        client.patch().uri("/api/v1/watch-spaces/" + roomId + "/settings")
                .headers(headers -> headers.setBearerAuth(guest.tokens().accessToken()))
                .bodyValue(Map.of("chatMuted", true))
                .exchange()
                .expectStatus().isForbidden();

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/invite-code")
                .headers(headers -> headers.setBearerAuth(guest.tokens().accessToken()))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(jdbc.queryForObject(
                "SELECT is_locked FROM watch_spaces WHERE id = ?", Boolean.class, roomId)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type IN
                    ('ROOM_LOCK_CHANGED', 'ROOM_SETTINGS_UPDATED', 'INVITE_CODE_ROTATED')
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
                """, titleId, "m4-controls-" + titleId, "M4 Controls Test Title",
                "A private fixture for watch-space control tests.", 60_000,
                "asset://m4-controls-test/video.mp4", createdBy);
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

    private void joinByIdExpectCode(
            AuthTestSupport.Account account, UUID roomId, String inviteCode, String expectedCode) {
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/join")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()))
                .bodyValue(Map.of("inviteCode", inviteCode))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo(expectedCode);
    }

    private Map<?, ?> joinByInvite(AuthTestSupport.Account account, String inviteCode) {
        return client.post().uri("/api/v1/watch-spaces/join")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()))
                .bodyValue(Map.of("inviteCode", inviteCode))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }
}
