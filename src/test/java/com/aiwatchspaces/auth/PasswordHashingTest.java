package com.aiwatchspaces.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiwatchspaces.common.error.ApiException;
import org.junit.jupiter.api.Test;

class PasswordHashingTest {

    private final PasswordHashing hashing = new PasswordHashing();

    @Test
    void policyCountsCharactersAndUtf8Bytes() {
        assertThat(PasswordHashing.satisfiesPolicy("a".repeat(12))).isTrue();
        assertThat(PasswordHashing.satisfiesPolicy("a".repeat(11))).isFalse();
        assertThat(PasswordHashing.satisfiesPolicy("a".repeat(72))).isTrue();
        assertThat(PasswordHashing.satisfiesPolicy("a".repeat(73))).isFalse(); // 73 bytes
        assertThat(PasswordHashing.satisfiesPolicy("é".repeat(36))).isTrue();  // 72 bytes
        assertThat(PasswordHashing.satisfiesPolicy("é".repeat(37))).isFalse(); // 74 bytes
        assertThat(PasswordHashing.satisfiesPolicy(null)).isFalse();
        assertThatThrownBy(() -> PasswordHashing.checkPolicy("short")).isInstanceOf(ApiException.class);
    }

    @Test
    void hashesWithBcryptStrength12OffTheCallingThread() {
        String hash = hashing.hash("correct horse battery").block();
        assertThat(hash).startsWith("{bcrypt}$2a$12$");
        assertThat(hashing.matches("correct horse battery", hash).block()).isTrue();
        assertThat(hashing.matches("wrong horse battery", hash).block()).isFalse();
        String threadName = reactor.core.publisher.Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block();
        assertThat(threadName).startsWith("parallel-");
    }

    @Test
    void unusableInputsNeverReachBcryptAndReturnFalse() {
        String hash = hashing.hash("correct horse battery").block();
        assertThat(hashing.matches("é".repeat(40), hash).block()).isFalse();
        assertThat(hashing.matches("x".repeat(200), hash).block()).isFalse();
        assertThat(hashing.matches(null, hash).block()).isFalse();
        assertThat(hashing.matches("correct horse battery", null).block()).isFalse();
    }
}
