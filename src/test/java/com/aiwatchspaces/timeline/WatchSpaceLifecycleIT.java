package com.aiwatchspaces.timeline;

import static org.assertj.core.api.Assertions.assertThat;

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

/** M4 leave/start/end transitions, timeline pinning and final room-ledger semantics. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceLifecycleIT {

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
    void leaveStartAndEndPersistTheLifecycleAndFinalLedgerEvent() {
        AuthTestSupport.Account host = newAccount();
        AuthTestSupport.Account firstGuest = newAccount();
        AuthTestSupport.Account secondGuest = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        Map<?, ?> created = createRoom(host, titleId);
        UUID roomId = UUID.fromString((String) created.get("watchSpaceId"));
        String inviteCode = (String) created.get("inviteCode");

        Map<?, ?> firstJoin = joinById(firstGuest, roomId, inviteCode);
        UUID firstMembershipId = UUID.fromString((String) firstJoin.get("membershipId"));

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/leave")
                .headers(headers -> headers.setBearerAuth(firstGuest.tokens().accessToken()))
                .exchange()
                .expectStatus().isNoContent();

        assertThat(jdbc.queryForObject("""
                SELECT leave_reason FROM watch_space_participants WHERE id = ?
                """, String.class, firstMembershipId)).isEqualTo("LEFT");

        // A retry after the membership has already closed is an idempotent no-op.
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/leave")
                .headers(headers -> headers.setBearerAuth(firstGuest.tokens().accessToken()))
                .exchange()
                .expectStatus().isNoContent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM room_events WHERE watch_space_id = ? AND event_type = 'PARTICIPANT_LEFT'",
                Integer.class, roomId)).isEqualTo(1);

        // Starting without a CURRENT valid timeline is rejected without changing room state.
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/start")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("NO_CURRENT_TIMELINE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM watch_spaces WHERE id = ?", String.class, roomId)).isEqualTo("SCHEDULED");

        UUID timelineVersionId = createCurrentTimeline(titleId, host.userId());
        UUID subtitleTrackId = createActiveSubtitle(titleId);

        Map<?, ?> started = client.post().uri("/api/v1/watch-spaces/" + roomId + "/start")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(started).isNotNull();
        assertThat(started.get("status")).isEqualTo("LIVE");
        assertThat(started.get("playbackState")).isEqualTo("PAUSED");
        assertThat(started.get("lastPlaybackPositionMs")).isEqualTo(0);
        assertThat(started.get("stateVersion")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT pinned_timeline_version_id FROM watch_spaces WHERE id = ?", UUID.class, roomId))
                .isEqualTo(timelineVersionId);
        assertThat(jdbc.queryForObject(
                "SELECT session_correlation_id FROM watch_spaces WHERE id = ?", UUID.class, roomId)).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT subtitle_track_id FROM room_subtitle_tracks
                WHERE watch_space_id = ? AND locale = 'en'
                """, UUID.class, roomId)).isEqualTo(subtitleTrackId);

        // Repeated start while LIVE returns the current state without another lifecycle event.
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/start")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM room_events WHERE watch_space_id = ? AND event_type = 'ROOM_STARTED'",
                Integer.class, roomId)).isEqualTo(1);

        joinById(secondGuest, roomId, inviteCode);

        Map<?, ?> ended = client.post().uri("/api/v1/watch-spaces/" + roomId + "/end")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        assertThat(ended).isNotNull();
        assertThat(ended.get("status")).isEqualTo("ENDED");
        assertThat(ended.get("lastSequenceNo")).isEqualTo(7);
        assertThat(ended.get("activeMembershipCount")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT ended_reason FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("HOST_ENDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND left_at IS NULL
                """, Integer.class, roomId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND left_at IS NOT NULL AND leave_reason = 'ROOM_ENDED'
                """, Integer.class, roomId)).isEqualTo(2);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT sequence_no, event_type
                FROM room_events WHERE watch_space_id = ?
                ORDER BY sequence_no
                """, roomId);
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly("ROOM_CREATED", "PARTICIPANT_JOINED", "PARTICIPANT_JOINED",
                        "PARTICIPANT_LEFT", "ROOM_STARTED", "PARTICIPANT_JOINED", "ROOM_ENDED");
        assertThat(events.getLast().get("sequence_no")).isEqualTo(7L);

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/end")
                .headers(headers -> headers.setBearerAuth(secondGuest.tokens().accessToken()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ROOM_ENDED");
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
                """, titleId, "m4-lifecycle-" + titleId, "M4 Lifecycle Test Title",
                "A private fixture for watch-space lifecycle tests.", 60_000,
                "asset://m4-lifecycle-test/video.mp4", createdBy);
        return titleId;
    }

    private UUID createCurrentTimeline(UUID titleId, UUID uploadedBy) {
        UUID timelineVersionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO title_timeline_versions
                    (id, title_id, version_number, status, source_format, raw_document, schema_version,
                     content_hash, validation_status, validation_errors, validated_at, uploaded_by,
                     published_at)
                VALUES (?, ?, 1, 'CURRENT', 'JSON', ?, 1, ?, 'VALID', '[]'::jsonb, now(), ?, now())
                """, timelineVersionId, titleId, "{\"schemaVersion\":1,\"events\":[]}",
                "a".repeat(64), uploadedBy);
        return timelineVersionId;
    }

    private UUID createActiveSubtitle(UUID titleId) {
        UUID subtitleTrackId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO subtitle_tracks
                    (id, title_id, locale, version_number, asset_uri, status)
                VALUES (?, ?, 'en', 1, 'asset://m4-lifecycle-test/en.vtt', 'ACTIVE')
                """, subtitleTrackId, titleId);
        return subtitleTrackId;
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
        return client.post().uri("/api/v1/watch-spaces/" + roomId + "/join")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()))
                .bodyValue(Map.of("inviteCode", inviteCode))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }
}
