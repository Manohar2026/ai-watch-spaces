package com.aiwatchspaces.watchspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
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

/** M4 scheduled-room unstarted expiry behavior against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = {
        "aiws.assets.directory=target/test-assets",
        "aiws.watchspace.unstarted-expiry=24h",
        "aiws.watchspace.expiry-scan-delay-ms=3600000"
})
class WatchSpaceUnstartedExpiryIT {

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

    private AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void expiresUnscheduledRoomTwentyFourHoursAfterCreatedAtAndAppendsFinalEvent() {
        AuthTestSupport.Account host = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        UUID roomId = createRoom(host, titleId);

        jdbc.update("""
                UPDATE watch_spaces
                SET created_at = now() - interval '25 hours'
                WHERE id = ?
                """, roomId);

        Integer expired = lanes.expireUnstartedRoomsNow().block(Duration.ofSeconds(15));
        assertThat(expired).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM watch_spaces WHERE id = ?", String.class, roomId)).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject(
                "SELECT ended_reason FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("UNSTARTED_EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND left_at IS NULL
                """, Integer.class, roomId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND left_at IS NOT NULL AND leave_reason = 'ROOM_ENDED'
                """, Integer.class, roomId)).isEqualTo(1);

        Timestamp endedAt = jdbc.queryForObject(
                "SELECT ended_at FROM watch_spaces WHERE id = ?", Timestamp.class, roomId);
        Timestamp eventAt = jdbc.queryForObject("""
                SELECT occurred_at FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_ENDED'
                """, Timestamp.class, roomId);
        assertThat(endedAt).isEqualTo(eventAt);

        assertThat(jdbc.queryForObject("""
                SELECT event_type FROM room_events
                WHERE watch_space_id = ? ORDER BY sequence_no DESC LIMIT 1
                """, String.class, roomId)).isEqualTo("ROOM_ENDED");
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'reason' FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_ENDED'
                """, String.class, roomId)).isEqualTo("UNSTARTED_EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(3L);

        // A second sweep sees only SCHEDULED rooms and cannot append another terminal event.
        assertThat(lanes.expireUnstartedRoomsNow().block(Duration.ofSeconds(15))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_ENDED'
                """, Integer.class, roomId)).isEqualTo(1);
    }

    @Test
    void scheduledAtTakesPrecedenceOverOlderCreatedAt() {
        AuthTestSupport.Account host = newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        UUID roomId = createRoom(host, titleId);

        jdbc.update("""
                UPDATE watch_spaces
                SET created_at = now() - interval '72 hours',
                    scheduled_at = now() - interval '2 hours'
                WHERE id = ?
                """, roomId);

        assertThat(lanes.expireUnstartedRoomsNow().block(Duration.ofSeconds(15))).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM watch_spaces WHERE id = ?", String.class, roomId)).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_ENDED'
                """, Integer.class, roomId)).isZero();

        jdbc.update("""
                UPDATE watch_spaces
                SET scheduled_at = now() - interval '25 hours'
                WHERE id = ?
                """, roomId);

        assertThat(lanes.expireUnstartedRoomsNow().block(Duration.ofSeconds(15))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT ended_reason FROM watch_spaces WHERE id = ?", String.class, roomId))
                .isEqualTo("UNSTARTED_EXPIRED");
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
                """, titleId, "m4-expiry-" + titleId, "M4 Expiry Test Title",
                "A private fixture for scheduled-room expiry tests.", 60_000,
                "asset://m4-expiry-test/video.mp4", createdBy);
        return titleId;
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
}
