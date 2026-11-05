package com.aiwatchspaces.watchspace;

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

/** M4 optimistic room-version guard rollback and stale-lane recovery against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceOptimisticGuardIT {

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
    void guardedUpdateWithZeroRowsRollsBackLedgerAndNextReadRehydratesLane() {
        AuthTestSupport.Account host = auth.newAccount();
        UUID titleId = createPublishedTitle(host.userId());
        UUID roomId = createRoom(host, titleId);

        // Simulate a change committed outside this process after the lane cached its initial snapshot.
        jdbc.update("UPDATE watch_spaces SET name = ? WHERE id = ?", "Persisted after lane hydration", roomId);
        installSuppressLockUpdateTrigger();

        try {
            client.post().uri("/api/v1/watch-spaces/" + roomId + "/lock")
                    .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                    .bodyValue(Map.of("locked", true))
                    .exchange()
                    .expectStatus().isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("CONFLICT_RETRY");

            // The event insert happened before the guarded update, but the transaction must roll it back.
            assertThat(jdbc.queryForObject(
                    "SELECT is_locked FROM watch_spaces WHERE id = ?", Boolean.class, roomId)).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM room_events
                    WHERE watch_space_id = ? AND event_type = 'ROOM_LOCK_CHANGED'
                    """, Integer.class, roomId)).isZero();

            // The failed mutation invalidated the in-memory snapshot. This read must show the DB value.
            client.get().uri("/api/v1/watch-spaces/" + roomId)
                    .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.name").isEqualTo("Persisted after lane hydration")
                    .jsonPath("$.locked").isEqualTo(false);
        } finally {
            removeSuppressLockUpdateTrigger();
        }

        // The same lane can accept a later command after the persisted snapshot has been reloaded.
        client.post().uri("/api/v1/watch-spaces/" + roomId + "/lock")
                .headers(headers -> headers.setBearerAuth(host.tokens().accessToken()))
                .bodyValue(Map.of("locked", true))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.locked").isEqualTo(true);

        assertThat(jdbc.queryForObject(
                "SELECT state_version FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM room_events
                WHERE watch_space_id = ? AND event_type = 'ROOM_LOCK_CHANGED'
                """, Integer.class, roomId)).isEqualTo(1);
    }

    private void installSuppressLockUpdateTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION m4_test_suppress_room_lock_update()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RETURN NULL;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER m4_test_suppress_room_lock_update
                BEFORE UPDATE OF is_locked ON watch_spaces
                FOR EACH ROW
                WHEN (NEW.is_locked IS TRUE)
                EXECUTE FUNCTION m4_test_suppress_room_lock_update()
                """);
    }

    private void removeSuppressLockUpdateTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS m4_test_suppress_room_lock_update ON watch_spaces");
        jdbc.execute("DROP FUNCTION IF EXISTS m4_test_suppress_room_lock_update()");
    }

    private UUID createPublishedTitle(UUID createdBy) {
        UUID titleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO titles
                    (id, slug, name, description, duration_ms, video_asset_uri, default_locale, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'en', 'PUBLISHED', ?)
                """, titleId, "m4-optimistic-guard-" + titleId, "M4 Optimistic Guard Test Title",
                "A private fixture for optimistic room-version guard tests.", 60_000,
                "asset://m4-optimistic-guard-test/video.mp4", createdBy);
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
