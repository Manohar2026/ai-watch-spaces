package com.aiwatchspaces.m2;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiwatchspaces.support.AuthTestSupport;
import com.aiwatchspaces.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** SYSTEM_DESIGN §8.8 / M2 exit criterion: no passwords, tokens, emails or the signing key in logs. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class NoSecretsInLogsIT {

    private static final String SIGNING_KEY = "dGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS0zMi1ieXRlcyE=";

    @Autowired
    WebTestClient client;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    @Test
    void authFlowsLogNoSecrets(CapturedOutput output) {
        AuthTestSupport auth = new AuthTestSupport(client, jdbc, encoder);
        String email = AuthTestSupport.uniqueEmail();
        auth.register(email, AuthTestSupport.PASSWORD, "Secret Tester");
        var first = auth.login(email, AuthTestSupport.PASSWORD);
        var second = auth.refreshOk(first.refreshToken());
        auth.refresh(first.refreshToken()).expectStatus().isUnauthorized(); // replay, logged as WARN
        client.post().uri("/api/v1/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("email", email, "password", "wrong password!!"))
                .exchange().expectStatus().isUnauthorized();

        String logs = output.getAll();
        assertThat(logs).contains("Login succeeded", "Refresh token reuse detected");
        assertThat(logs).doesNotContain(AuthTestSupport.PASSWORD, "wrong password!!", email,
                first.accessToken(), first.refreshToken(), second.accessToken(), second.refreshToken(), SIGNING_KEY);
        assertThat(logs).doesNotContain("$2a$12$");
    }
}
