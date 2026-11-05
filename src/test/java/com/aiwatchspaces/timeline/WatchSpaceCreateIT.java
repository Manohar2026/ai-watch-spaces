package com.aiwatchspaces.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** M4 room creation, first membership and ordered ledger events against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class WatchSpaceCreateIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    SampleContentSeeder seeder;

    private AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void createsScheduledRoomWithHashedOneTimeInviteAndInitialLedger() {
        AuthTestSupport.Account account = auth.newAccount();
        seeder.seed(account.userId());

        Map<?, ?> response = create(account, Map.of("titleId", SampleContentSeeder.TITLE_ID.toString()))
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        UUID roomId = UUID.fromString((String) response.get("watchSpaceId"));
        String inviteCode = (String) response.get("inviteCode");
        assertThat(inviteCode).matches("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}");
        assertThat(response.get("status")).isEqualTo("SCHEDULED");
        assertThat(response.get("hostUserId")).isEqualTo(account.userId().toString());

        Map<?, ?> settings = (Map<?, ?>) response.get("settings");
        assertThat(settings.get("maxParticipants")).isEqualTo(25);
        assertThat(settings.get("aiVerbosity")).isEqualTo("NORMAL");
        assertThat(settings.get("triviaEnabled")).isEqualTo(true);
        assertThat(settings.get("votingEnabled")).isEqualTo(true);

        String storedHash = jdbc.queryForObject(
                "SELECT invite_code_hash FROM watch_spaces WHERE id = ?", String.class, roomId);
        assertThat(storedHash).hasSize(64).isNotEqualTo(inviteCode).doesNotContain("-");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants
                WHERE watch_space_id = ? AND user_id = ? AND left_at IS NULL
                """, Integer.class, roomId, account.userId())).isEqualTo(1);

        assertThat(jdbc.queryForList("""
                SELECT sequence_no, event_type
                FROM room_events
                WHERE watch_space_id = ?
                ORDER BY sequence_no
                """, roomId))
                .extracting(row -> row.get("event_type"))
                .containsExactly("ROOM_CREATED", "PARTICIPANT_JOINED");
        assertThat(jdbc.queryForObject(
                "SELECT last_sequence_no FROM watch_spaces WHERE id = ?", Long.class, roomId)).isEqualTo(2L);
    }

    @Test
    void rejectsTheSixthNonEndedRoomForOneHost() {
        AuthTestSupport.Account account = auth.newAccount();
        seeder.seed(account.userId());

        for (int i = 0; i < 5; i++) {
            create(account, Map.of("titleId", SampleContentSeeder.TITLE_ID.toString()))
                    .expectStatus().isCreated();
        }
        create(account, Map.of("titleId", SampleContentSeeder.TITLE_ID.toString()))
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("HOST_ROOM_LIMIT");
    }

    private WebTestClient.ResponseSpec create(AuthTestSupport.Account account, Map<String, Object> body) {
        return client.post().uri("/api/v1/watch-spaces")
                .headers(headers -> headers.setBearerAuth(account.tokens().accessToken()))
                .bodyValue(body)
                .exchange();
    }
}
