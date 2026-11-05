package com.aiwatchspaces.watchspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.ratelimit.RateLimiter;
import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** M4 graceful shutdown: readiness, write rejection, lane drain and authoritative playback pause. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WatchSpaceGracefulShutdownIT {

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
    ApplicationContext applicationContext;

    @Autowired
    @Qualifier("realtimeHealthIndicator")
    ReactiveHealthIndicator realtimeHealth;

    private AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void readinessDropsBeforeLiveRoomIsPausedAndNewWritesAreRejected() {
        AuthTestSupport.Account host = auth.newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        createCurrentTimeline(titleId, host.userId());
        UUID roomId = createRoom(host, titleId);
        startRoom(host, roomId);

        Instant playbackUpdatedAt = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                UPDATE watch_spaces
                SET playback_state = 'PLAYING',
                    last_playback_position_ms = 12_345,
                    playback_updated_at = ?
                WHERE id = ?
                """, Timestamp.from(playbackUpdatedAt), roomId);

        // Simulate a stale in-process snapshot so the final shutdown command must hydrate PostgreSQL.
        assertThatThrownBy(() -> lanes.submit(roomId, (current, mutationInstant) -> {
            throw new ApiException(ErrorCode.CONFLICT_RETRY, "Force lane rehydration for this test.");
        }).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.CONFLICT_RETRY));

        applicationContext.publishEvent(new ContextClosedEvent(applicationContext));

        assertThat(realtimeHealth.health().block(Duration.ofSeconds(5)).getStatus().getCode())
                .isEqualTo("OUT_OF_SERVICE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM watch_spaces WHERE id = ?", String.class, roomId)).isEqualTo("LIVE");
        assertThat(jdbc.queryForObject(
                "SELECT playback_state FROM watch_spaces WHERE id = ?", String.class, roomId)).isEqualTo("PAUSED");

        Integer position = jdbc.queryForObject(
                "SELECT last_playback_position_ms FROM watch_spaces WHERE id = ?", Integer.class, roomId);
        Timestamp persistedUpdatedAt = jdbc.queryForObject(
                "SELECT playback_updated_at FROM watch_spaces WHERE id = ?", Timestamp.class, roomId);
        Timestamp pauseOccurredAt = jdbc.queryForObject("""
                SELECT occurred_at FROM room_events
                WHERE watch_space_id = ? AND event_type = 'PLAYBACK_PAUSE'
                """, Timestamp.class, roomId);
        String pauseReason = jdbc.queryForObject("""
                SELECT payload ->> 'reason' FROM room_events
                WHERE watch_space_id = ? AND event_type = 'PLAYBACK_PAUSE'
                """, String.class, roomId);

        long elapsedMillis = Math.max(0L, Duration.between(playbackUpdatedAt, pauseOccurredAt.toInstant()).toMillis());
        int expectedPosition = (int) Math.min(60_000L, 12_345L + elapsedMillis);
        assertThat(position).isEqualTo(expectedPosition);
        assertThat(persistedUpdatedAt).isEqualTo(pauseOccurredAt);
        assertThat(pauseReason).isEqualTo("SHUTDOWN");
        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(4L);

        client.post().uri("/api/v1/watch-spaces/" + roomId + "/lock")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("locked", true))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_BUSY");
    }

    private UUID createPublishedTitle(UUID createdBy) {
        UUID titleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO titles
                    (id, slug, name, description, duration_ms, video_asset_uri, default_locale, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'en', 'PUBLISHED', ?)
                """, titleId, "m4-shutdown-" + titleId, "M4 Shutdown Test Title",
                "A private fixture for graceful room shutdown tests.", 60_000,
                "asset://m4-shutdown-test/video.mp4", createdBy);
        return titleId;
    }

    private void createCurrentTimeline(UUID titleId, UUID uploadedBy) {
        jdbc.update("""
                INSERT INTO title_timeline_versions
                    (id, title_id, version_number, status, source_format, raw_document, schema_version,
                     content_hash, validation_status, validation_errors, validated_at, uploaded_by, published_at)
                VALUES (?, ?, 1, 'CURRENT', 'JSON', ?, 1, ?, 'VALID', '[]'::jsonb, now(), ?, now())
                """, UUID.randomUUID(), titleId, "{\"schemaVersion\":1,\"events\":[]}",
                "d".repeat(64), uploadedBy);
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
