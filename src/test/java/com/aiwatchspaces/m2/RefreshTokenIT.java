package com.aiwatchspaces.m2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.AuthTestSupport.Tokens;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Refresh rotation and replay rejection (SYSTEM_DESIGN §8.2). Includes the three required M2 tests:
 * committed family revocation, newest-token failure after replay, and fault injection on the
 * revocation UPDATE. Every database check is a separate read after the HTTP response.
 */
@IntegrationTest
class RefreshTokenIT {

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    AuthTestSupport auth;

    @BeforeEach
    void setUp() {
        auth = new AuthTestSupport(client, jdbc, encoder);
    }

    @Test
    void refreshRotatesWithinTheFamily() {
        var account = auth.newAccount();
        Tokens first = account.tokens();
        Tokens second = auth.refreshOk(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(second.user()).isNull();
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(second.accessToken())).exchange()
                .expectStatus().isOk();

        List<Map<String, Object>> rows = auth.familyRows(account.userId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("family_id")).isEqualTo(rows.get(1).get("family_id"));
        assertThat(rows.get(0)).containsEntry("revoked_reason", "ROTATED")
                .containsEntry("replaced_by_token_id", rows.get(1).get("id"));
        assertThat(rows.get(1).get("revoked_reason")).isNull();
        assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class,
                account.userId())).allMatch(hash -> hash.matches("[0-9a-f]{64}"))
                .doesNotContain(first.refreshToken(), second.refreshToken());
    }

    @Test
    void eachLoginStartsANewFamily() {
        var account = auth.newAccount();
        auth.login(account.email(), AuthTestSupport.PASSWORD);
        assertThat(auth.familyRows(account.userId()).stream().map(r -> r.get("family_id")).distinct()).hasSize(2);
    }

    /** Required test 1: the replay 401 implies the family revocation is committed. */
    @Test
    void replayRevokesTheWholeFamilyAndTheRevocationIsCommitted() {
        var account = auth.newAccount();
        Tokens t1 = account.tokens();
        Tokens t2 = auth.refreshOk(t1.refreshToken());
        Tokens t3 = auth.refreshOk(t2.refreshToken());

        auth.refresh(t1.refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");

        // Separate read on another connection after the response.
        List<Map<String, Object>> rows = auth.familyRows(account.userId());
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("revoked_reason")).isIn("ROTATED", "REUSE_DETECTED");
            assertThat(row.get("revoked_at")).isNotNull();
        });
        assertThat(rows.get(2)).containsEntry("revoked_reason", "REUSE_DETECTED");
        assertThat(t3.refreshToken()).isNotBlank();
    }

    /** Required test 2: after a replay, the newest token of the family no longer works. */
    @Test
    void newestTokenFailsAfterReplay() {
        var account = auth.newAccount();
        Tokens t1 = account.tokens();
        Tokens t2 = auth.refreshOk(t1.refreshToken());

        auth.refresh(t1.refreshToken()).expectStatus().isUnauthorized();
        auth.refresh(t2.refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(auth.familyRows(account.userId())).allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
    }

    /** Required test 3: a failing revocation UPDATE yields 503 (never 401) and changes nothing. */
    @Test
    void failedRevocationIsNot401AndLeavesTokenStateUnchanged() {
        var account = auth.newAccount();
        Tokens t1 = account.tokens();
        Tokens t2 = auth.refreshOk(t1.refreshToken());
        List<Map<String, Object>> before = auth.familyRows(account.userId());
        assertThat(before.get(1).get("revoked_reason")).isNull();

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION m2_fail_reuse_revocation() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected failure in revocation update'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("""
                CREATE TRIGGER m2_fail_reuse_revocation BEFORE UPDATE ON refresh_tokens
                FOR EACH ROW WHEN (NEW.revoked_reason = 'REUSE_DETECTED')
                EXECUTE FUNCTION m2_fail_reuse_revocation()""");
        try {
            auth.refresh(t1.refreshToken()).expectStatus().isEqualTo(503)
                    .expectHeader().exists("Retry-After")
                    .expectBody().jsonPath("$.code").isEqualTo("SERVICE_BUSY");
        } finally {
            jdbc.execute("DROP TRIGGER m2_fail_reuse_revocation ON refresh_tokens");
            jdbc.execute("DROP FUNCTION m2_fail_reuse_revocation()");
        }
        assertThat(auth.familyRows(account.userId())).isEqualTo(before);

        // Without the fault the replay is detected and committed as usual.
        auth.refresh(t1.refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");
        auth.refresh(t2.refreshToken()).expectStatus().isUnauthorized();
    }

    @Test
    void concurrentUseOfOneTokenRotatesOnceAndRevokesTheFamily() throws Exception {
        var account = auth.newAccount();
        String token = account.tokens().refreshToken();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<Integer>> calls = List.of(
                    CompletableFuture.supplyAsync(() -> refreshStatus(start, token), pool),
                    CompletableFuture.supplyAsync(() -> refreshStatus(start, token), pool));
            start.countDown();
            List<Integer> statuses = calls.stream().map(CompletableFuture::join).sorted().toList();
            assertThat(statuses).containsExactly(200, 401);
        } finally {
            pool.shutdownNow();
        }
        assertThat(auth.familyRows(account.userId())).hasSize(2)
                .allSatisfy(row -> assertThat(row.get("revoked_at")).isNotNull());
    }

    @Test
    void unknownAndExpiredTokensAreInvalidAndChangeNothing() {
        auth.refresh("unknown-" + UUID.randomUUID()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_INVALID");

        var account = auth.newAccount();
        jdbc.update("""
                UPDATE refresh_tokens SET created_at = now() - interval '20 days', expires_at = now() - interval '1 day'
                WHERE user_id = ?""", account.userId());
        auth.refresh(account.tokens().refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_INVALID");
        assertThat(auth.familyRows(account.userId())).singleElement()
                .satisfies(row -> assertThat(row.get("revoked_at")).isNull());
    }

    /** §8.2 precedence: a revoked token that has also expired is still a replay, and the revocation commits. */
    @Test
    void revokedAndExpiredTokenIsStillAReplay() {
        var account = auth.newAccount();
        Tokens t1 = account.tokens();
        Tokens t2 = auth.refreshOk(t1.refreshToken());
        UUID t1Id = (UUID) auth.familyRows(account.userId()).get(0).get("id");
        expire(t1Id);

        auth.refresh(t1.refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");

        List<Map<String, Object>> rows = auth.familyRows(account.userId());
        assertThat(rows).extracting(row -> row.get("revoked_reason")).containsExactlyInAnyOrder("ROTATED", "REUSE_DETECTED");
        auth.refresh(t2.refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");
    }

    /** §8.2 precedence: an expired token that was never revoked is just invalid; nothing in the family changes. */
    @Test
    void expiredActiveTokenIsInvalidAndRevokesNothing() {
        var account = auth.newAccount();
        Tokens t1 = account.tokens();
        Tokens t2 = auth.refreshOk(t1.refreshToken());
        UUID t2Id = (UUID) auth.familyRows(account.userId()).get(1).get("id");
        expire(t2Id);
        List<Map<String, Object>> before = auth.familyRows(account.userId());

        auth.refresh(t2.refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_INVALID");

        assertThat(auth.familyRows(account.userId())).isEqualTo(before);
        assertThat(before).extracting(row -> row.get("revoked_reason")).containsExactlyInAnyOrder("ROTATED", null);
    }

    @Test
    void logoutWithoutAccessTokenRevokesExactlyThatFamily() {
        var alice = auth.newAccount();
        Tokens aliceOtherSession = auth.login(alice.email(), AuthTestSupport.PASSWORD);
        var bob = auth.newAccount();
        Tokens t2 = auth.refreshOk(alice.tokens().refreshToken());
        UUID loggedOutFamily = (UUID) jdbc.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE replaced_by_token_id IS NOT NULL AND user_id = ?",
                UUID.class, alice.userId());

        logout(null, t2.refreshToken()).expectStatus().isNoContent();

        List<Map<String, Object>> aliceRows = auth.familyRows(alice.userId());
        assertThat(aliceRows).filteredOn(row -> loggedOutFamily.equals(row.get("family_id")))
                .extracting(row -> row.get("revoked_reason")).containsExactlyInAnyOrder("ROTATED", "LOGOUT");
        assertThat(aliceRows).filteredOn(row -> !loggedOutFamily.equals(row.get("family_id")))
                .singleElement().satisfies(row -> assertThat(row.get("revoked_at")).isNull());
        assertThat(auth.familyRows(bob.userId())).singleElement()
                .satisfies(row -> assertThat(row.get("revoked_at")).isNull());

        auth.refreshOk(aliceOtherSession.refreshToken());
        auth.refreshOk(bob.tokens().refreshToken());
        auth.refresh(t2.refreshToken()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_REUSED");
    }

    @Test
    void logoutWithExpiredOrInvalidAccessTokenStillWorks() {
        var account = auth.newAccount();
        Instant now = Instant.now();
        String expiredAccess = auth.forge(account.userId(), "USER",
                c -> c.issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(120)));
        client.get().uri("/api/v1/me").headers(h -> h.setBearerAuth(expiredAccess)).exchange()
                .expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("TOKEN_EXPIRED");

        logout(expiredAccess, account.tokens().refreshToken()).expectStatus().isNoContent();
        assertThat(auth.familyRows(account.userId())).singleElement()
                .satisfies(row -> assertThat(row.get("revoked_reason")).isEqualTo("LOGOUT"));

        var other = auth.newAccount();
        logout("garbage.token.value", other.tokens().refreshToken()).expectStatus().isNoContent();
        assertThat(auth.familyRows(other.userId())).singleElement()
                .satisfies(row -> assertThat(row.get("revoked_reason")).isEqualTo("LOGOUT"));
    }

    @Test
    void logoutIsIdempotentAndUnknownTokensAreInvalid() {
        var account = auth.newAccount();
        Tokens t1 = account.tokens();
        Tokens t2 = auth.refreshOk(t1.refreshToken());

        logout(null, t2.refreshToken()).expectStatus().isNoContent();
        logout(null, t2.refreshToken()).expectStatus().isNoContent();
        logout(null, t1.refreshToken()).expectStatus().isNoContent(); // already rotated: still 204
        assertThat(auth.familyRows(account.userId())).extracting(row -> row.get("revoked_reason"))
                .containsExactly("ROTATED", "LOGOUT");

        var expiredAccount = auth.newAccount();
        expire((UUID) auth.familyRows(expiredAccount.userId()).get(0).get("id"));
        logout(null, expiredAccount.tokens().refreshToken()).expectStatus().isNoContent();
        assertThat(auth.familyRows(expiredAccount.userId())).singleElement()
                .satisfies(row -> assertThat(row.get("revoked_reason")).isEqualTo("LOGOUT"));

        logout(null, "unknown-" + UUID.randomUUID()).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void publicAuthRoutesIgnoreAStaleAuthorizationHeader() {
        var account = auth.newAccount();
        Instant now = Instant.now();
        String expiredAccess = auth.forge(account.userId(), "USER",
                c -> c.issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(120)));
        client.post().uri("/api/v1/auth/refresh").headers(h -> h.setBearerAuth(expiredAccess))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("refreshToken", account.tokens().refreshToken()))
                .exchange().expectStatus().isOk();
        client.post().uri("/api/v1/auth/login").headers(h -> h.setBearerAuth("garbage"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", account.email(), "password", AuthTestSupport.PASSWORD))
                .exchange().expectStatus().isOk();
    }

    private int refreshStatus(CountDownLatch start, String token) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return auth.refresh(token).returnResult(String.class).getStatus().value();
    }

    /** Logout with an optional (possibly invalid) access token; {@code null} sends no Authorization header. */
    private WebTestClient.ResponseSpec logout(String accessToken, String refreshToken) {
        return client.post().uri("/api/v1/auth/logout").headers(h -> {
                    if (accessToken != null) {
                        h.setBearerAuth(accessToken);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("refreshToken", refreshToken)).exchange();
    }

    private void expire(UUID tokenId) {
        assertThat(jdbc.update("""
                UPDATE refresh_tokens SET created_at = now() - interval '20 days', expires_at = now() - interval '1 day'
                WHERE id = ?""", tokenId)).isEqualTo(1);
    }
}
