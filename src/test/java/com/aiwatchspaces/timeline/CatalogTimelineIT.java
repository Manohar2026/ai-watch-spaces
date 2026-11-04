package com.aiwatchspaces.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** End-to-end M3 reads and sample seed checks against PostgreSQL 17. */
@IntegrationTest
@TestPropertySource(properties = "aiws.assets.directory=target/test-assets")
class CatalogTimelineIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    DbExecutor db;

    @Autowired
    SampleContentSeeder seeder;

    AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void seededTitleTimelineAndSubtitleReadsWorkAndAssetsMatchChecksums() throws Exception {
        AuthTestSupport.Account account = auth.newAccount();
        String token = account.tokens().accessToken();

        SampleContentSeeder.SeedResult first = db.call(() -> seeder.seed(account.userId())).block();
        assertThat(first).isNotNull();
        assertThat(first.alreadySeeded()).isFalse();
        SampleContentSeeder.SeedResult second = db.call(() -> seeder.seed(account.userId())).block();
        assertThat(second).isNotNull();
        assertThat(second.alreadySeeded()).isTrue();

        assertThat(jdbc.queryForObject("""
                SELECT validation_status FROM title_timeline_versions WHERE id = ?
                """, String.class, SampleContentSeeder.TIMELINE_VERSION_ID)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM timeline_events WHERE timeline_version_id = ?
                """, Integer.class, SampleContentSeeder.TIMELINE_VERSION_ID)).isEqualTo(4);

        client.get().uri("/api/v1/titles?page=0&size=10&genre=nature")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(10)
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.items[0].slug").isEqualTo("the-first-bloom")
                .jsonPath("$.items[0].genres[0]").isEqualTo("nature");

        client.get().uri("/api/v1/titles/" + SampleContentSeeder.TITLE_ID)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(SampleContentSeeder.TITLE_ID.toString())
                .jsonPath("$.durationMs").isEqualTo(5_000)
                .jsonPath("$.defaultLocale").isEqualTo("en");

        // from/to are seconds; returned positions are milliseconds and ts is seconds.
        client.get().uri("/api/v1/titles/" + SampleContentSeeder.TITLE_ID + "/timeline?from=1&to=3")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.timelineVersionId").isEqualTo(SampleContentSeeder.TIMELINE_VERSION_ID.toString())
                .jsonPath("$.events.length()").isEqualTo(4)
                .jsonPath("$.events[0].startMs").isEqualTo(0)
                .jsonPath("$.events[0].ts").isEqualTo(0)
                .jsonPath("$.events[3].startMs").isEqualTo(2_000)
                .jsonPath("$.events[3].ts").isEqualTo(2);

        // The range [2s, 2s] includes the scene interval and the point event at 2s only.
        client.get().uri("/api/v1/titles/" + SampleContentSeeder.TITLE_ID + "/timeline?from=2&to=2")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.events.length()").isEqualTo(2);

        client.get().uri("/api/v1/titles/" + SampleContentSeeder.TITLE_ID + "/timeline?from=4&to=2")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.details[0].code").isEqualTo("INVALID_RANGE");

        client.get().uri("/api/v1/titles/" + SampleContentSeeder.TITLE_ID + "/timeline?from=0&to=3601")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isBadRequest();

        client.get().uri("/api/v1/titles/" + SampleContentSeeder.TITLE_ID + "/subtitles?locale=en-IN")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.locale").isEqualTo("en-IN")
                .jsonPath("$.versionNumber").isEqualTo(1)
                .jsonPath("$.checksum").isNotEmpty();

        Path immutableAsset = Path.of("target/test-assets/subtitles/the-first-bloom.en.vtt");
        byte[] originalAsset = Files.readAllBytes(immutableAsset);
        try {
            Files.write(immutableAsset, new byte[] {1, 2, 3});
            assertThatThrownBy(() -> db.call(() -> seeder.seed(account.userId())).block())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("immutable");
        } finally {
            Files.write(immutableAsset, originalAsset);
        }

        assertAssetChecksum("the-first-bloom.en.vtt", "en");
        assertAssetChecksum("the-first-bloom.en-IN.vtt", "en-IN");
    }

    private void assertAssetChecksum(String fileName, String locale) throws Exception {
        String checksum = jdbc.queryForObject("""
                SELECT checksum FROM subtitle_tracks WHERE title_id = ? AND locale = ? AND status = 'ACTIVE'
                """, String.class, SampleContentSeeder.TITLE_ID, locale);
        Path asset = Path.of("target/test-assets/subtitles", fileName);
        assertThat(Files.exists(asset)).isTrue();
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(asset)));
        assertThat(actual).isEqualTo(checksum);
    }
}
