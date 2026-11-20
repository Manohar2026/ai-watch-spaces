package com.aiwatchspaces.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Admin catalogue creation and safe archival against PostgreSQL 17. */
@IntegrationTest
class CatalogManagementIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    private AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void adminCanCreateMultipleTitlesAndArchiveWithoutDeletingHistory() {
        String adminEmail = AuthTestSupport.uniqueEmail();
        UUID adminId = auth.register(adminEmail, AuthTestSupport.PASSWORD, "Catalogue Admin");
        auth.setSystemRole(adminId, "ADMIN");
        String adminToken = auth.login(adminEmail, AuthTestSupport.PASSWORD).accessToken();

        AuthTestSupport.Account regular = auth.newAccount();

        UUID firstId = createTitle(adminToken, "First Added Story");
        UUID secondId = createTitle(adminToken, "Second Added Story");
        assertThat(firstId).isNotEqualTo(secondId);

        client.get().uri("/api/v1/titles?page=0&size=50")
                .headers(headers -> headers.setBearerAuth(regular.tokens().accessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[?(@.id=='" + firstId + "')]").isNotEmpty()
                .jsonPath("$.items[?(@.id=='" + secondId + "')]").isNotEmpty();

        client.delete().uri("/api/v1/admin/titles/" + firstId)
                .headers(headers -> headers.setBearerAuth(regular.tokens().accessToken()))
                .exchange()
                .expectStatus().isForbidden();

        client.delete().uri("/api/v1/admin/titles/" + firstId)
                .headers(headers -> headers.setBearerAuth(adminToken))
                .exchange()
                .expectStatus().isNoContent();

        client.get().uri("/api/v1/titles/" + firstId)
                .headers(headers -> headers.setBearerAuth(regular.tokens().accessToken()))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(jdbc.queryForObject("SELECT status FROM titles WHERE id = ?", String.class, firstId))
                .isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM titles WHERE id = ?", Integer.class, firstId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM titles WHERE id = ?", String.class, secondId))
                .isEqualTo("PUBLISHED");
    }

    private UUID createTitle(String token, String name) {
        Map<String, Object> body = Map.of(
                "name", name,
                "description", "A user-added demonstration title.",
                "durationMs", 300_000,
                "videoAssetUri", "https://example.test/media/demo.mp4",
                "defaultLocale", "en-IN",
                "genres", List.of("Drama", "Nature", "drama"));

        Map<?, ?> created = client.post().uri("/api/v1/admin/titles")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.get("slug").toString()).startsWith(name.toLowerCase().replace(' ', '-'));
        assertThat(created.get("defaultLocale")).isEqualTo("en-IN");
        assertThat(created.get("genres")).isEqualTo(List.of("drama", "nature"));
        return UUID.fromString(created.get("id").toString());
    }
}
