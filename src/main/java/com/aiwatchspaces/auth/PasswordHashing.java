package com.aiwatchspaces.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * BCrypt strength 12 via {@link DelegatingPasswordEncoder} (SYSTEM_DESIGN §8.5). Hashing is
 * CPU-bound and runs on Reactor's {@code parallel} scheduler, never on a Netty event loop or a
 * {@code db-*} thread (Appendix E #1).
 */
@Component
class PasswordHashing {

    static final int BCRYPT_STRENGTH = 12;
    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;
    static final int MAX_UTF8_BYTES = 72;

    private final PasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt",
            Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH)));
    private final Scheduler scheduler = Schedulers.parallel();
    /** Hash compared against when the user is unknown or the password is unusable, so timing matches. */
    private final String dummyHash = encoder.encode("dummy-password-for-timing-only");

    /** Registration policy: 12–128 characters and at most 72 UTF-8 bytes; otherwise 400 VALIDATION_FAILED. */
    static void checkPolicy(String password) {
        if (!satisfiesPolicy(password)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new ErrorDetail("password", "PASSWORD_POLICY",
                            "must be 12-128 characters and at most 72 UTF-8 bytes")));
        }
    }

    static boolean satisfiesPolicy(String password) {
        if (password == null) {
            return false;
        }
        int length = password.codePointCount(0, password.length());
        return length >= MIN_LENGTH && length <= MAX_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    Mono<String> hash(String password) {
        return Mono.fromCallable(() -> encoder.encode(password)).subscribeOn(scheduler);
    }

    /**
     * Verifies a login password. A {@code null} hash (unknown user) or a password BCrypt cannot take
     * (over 72 bytes / 128 characters) is checked against a dummy hash and returns {@code false}.
     */
    Mono<Boolean> matches(String password, String storedHash) {
        return Mono.fromCallable(() -> {
            boolean usable = password != null && password.length() <= MAX_LENGTH
                    && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
            if (!usable || storedHash == null) {
                encoder.matches("dummy-password-for-timing-only-x", dummyHash);
                return false;
            }
            return encoder.matches(password, storedHash);
        }).subscribeOn(scheduler);
    }
}
