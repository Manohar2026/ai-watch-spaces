package com.aiwatchspaces.watchspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
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

/** M4 recovery of persisted live rooms before the application reports readiness. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceStartupRecoveryIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    RoomLaneRegistry lanes;

    @Autowired
    RoomRecoveryStatus recoveryStatus;

    private AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        auth = new AuthTestSupport(client, jdbc, encoder);
        assertThat(recoveryStatus.isCompleted()).isTrue();
    }

    @Test
    void recoveryPausesPlayingSnapshotAndAppendsPauseThenRuntimeRecovered() {
        AuthTestSupport.Account host = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        createCurrentTimeline(titleId, host.userId());
        UUID roomId = createRoom(host, titleId);
        startRoom(host, roomId);
        UUID sessionId = jdbc.queryForObject(
                "SELECT session_correlation_id FROM watch_spaces WHERE id = ?", UUID.class, roomId);

        jdbc.update("""
                UPDATE watch_spaces
                SET playback_state = 'PLAYING',
                    last_playback_position_ms = 12_345,
                    playback_updated_at = now() - interval '10 minutes'
                WHERE id = ?
                """, roomId);

        assertThat(lanes.recoverRoomsNow().block(Duration.ofSeconds(15))).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("HOST_DISCONNECTED");
        assertThat(jdbc.queryForObject(
                "SELECT playback_state FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject(
                "SELECT last_playback_position_ms FROM watch_spaces WHERE id = ?", Integer.class, roomId))
                .isEqualTo(12_345);
        assertThat(jdbc.queryForObject(
                "SELECT session_correlation_id FROM watch_spaces WHERE id = ?", UUID.class, roomId))
                .isEqualTo(sessionId);

        Timestamp disconnectedAt = jdbc.queryForObject(
                "SELECT host_disconnected_at FROM watch_spaces WHERE id = ?", Timestamp.class, roomId);
        Timestamp playbackUpdatedAt = jdbc.queryForObject(
                "SELECT playback_updated_at FROM watch_spaces WHERE id = ?", Timestamp.class, roomId);
        Timestamp recoveredEventAt = jdbc.queryForObject("""
                SELECT occurred_at FROM room_events
                WHERE watch_space_id = ? AND event_type = 'RUNTIME_RECOVERED'
                """, Timestamp.class, roomId);
        assertThat(disconnectedAt).isEqualTo(recoveredEventAt);
        assertThat(playbackUpdatedAt).isEqualTo(recoveredEventAt);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT sequence_no, event_type, playback_position_ms, payload, correlation_id, occurred_at
                FROM room_events
                WHERE watch_space_id = ?
                ORDER BY sequence_no
                """, roomId);
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly("ROOM_CREATED", "PARTICIPANT_JOINED", "ROOM_STARTED",
                        "PLAYBACK_PAUSE", "RUNTIME_RECOVERED");
        assertThat(events.get(3).get("playback_position_ms")).isEqualTo(12_345);
        assertThat(events.get(3).get("correlation_id")).isEqualTo(sessionId);
        assertThat(events.get(4).get("correlation_id")).isEqualTo(sessionId);
        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(5L);
    }

    @Test
    void recoveryOfAlreadyPausedRoomDoesNotInventPlaybackPauseEvent() {
        AuthTestSupport.Account host = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        createCurrentTimeline(titleId, host.userId());
        UUID roomId = createRoom(host, titleId);
        startRoom(host, roomId);

        assertThat(jdbc.queryForObject(
                "SELECT playback_state FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("PAUSED");
        assertThat(lanes.recoverRoomsNow().block(Duration.ofSeconds(15))).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("HOST_DISCONNECTED");
        assertThat(jdbc.queryForObject(
                "SELECT playback_state FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'PLAYBACK_PAUSE'
                """, Integer.class, roomId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'RUNTIME_RECOVERED'
                """, Integer.class, roomId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(4L);
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
                """, titleId, "m4-recovery-" + titleId, "M4 Recovery Test Title",
                "A private fixture for startup recovery tests.", 60_000,
                "asset://m4-recovery-test/video.mp4", createdBy);
        return titleId;
    }

    private void createCurrentTimeline(UUID titleId, UUID uploadedBy) {
        jdbc.update("""
                INSERT INTO title_timeline_versions
                    (id, title_id, version_number, status, source_format, raw_document, schema_version,
                     content_hash, validation_status, validation_errors, validated_at, uploaded_by, published_at)
                VALUES (?, ?, 1, 'CURRENT', 'JSON', ?, 1, ?, 'VALID', '[]'::jsonb, now(), ?, now())
                """, UUID.randomUUID(), titleId, "{\"schemaVersion\":1,\"events\":[]}",
                "b".repeat(64), uploadedBy);
    }

    private UUID createRoom(AuthTestSupport.Account host, UUID titleId) {
        Map<?, ?> response = client.post().uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("titleId", titleId.toString()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return UUID.fromString((String) response.get("watchSpaceId"));
    }

    private void startRoom(AuthTestSupport.Account host, UUID roomId) {
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/start")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("LIVE")
                .jsonPath("$.playbackState").isEqualTo("PAUSED");
    }
}
