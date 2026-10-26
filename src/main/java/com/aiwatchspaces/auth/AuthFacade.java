package com.aiwatchspaces.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.aiwatchspaces.auth.web.AuthDtos.LoginRequest;
import com.aiwatchspaces.auth.web.AuthDtos.LoginResponse;
import com.aiwatchspaces.auth.web.AuthDtos.RefreshRequest;
import com.aiwatchspaces.auth.web.AuthDtos.RegisterRequest;
import com.aiwatchspaces.auth.web.AuthDtos.RegisterResponse;
import com.aiwatchspaces.auth.web.AuthDtos.TokenPairResponse;
import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.ratelimit.RateLimiter;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.text.PlainText;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.users.EmailAddresses;
import com.aiwatchspaces.users.NewUserAccount;
import com.aiwatchspaces.users.UserAccounts;
import com.aiwatchspaces.users.UserCredentials;
import com.aiwatchspaces.users.UserProfile;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;

/**
 * Reactive adapter for authentication. Each blocking service call is wrapped once with
 * {@link DbExecutor#call}; outcomes are mapped to HTTP errors only after the transaction has
 * committed (ADR-003 §3–§4). Never logs passwords, tokens or email addresses.
 */
@Component
public class AuthFacade {

    private static final Logger log = LoggerFactory.getLogger(AuthFacade.class);

    private final UserAccounts userAccounts;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final PasswordHashing passwords;
    private final DbExecutor db;
    private final RateLimiter rateLimiter;
    private final AuthRateLimitProperties limits;
    private final Clock clock;

    AuthFacade(UserAccounts userAccounts, RefreshTokenService refreshTokens, AccessTokenIssuer accessTokens,
               PasswordHashing passwords, DbExecutor db, RateLimiter rateLimiter, AuthRateLimitProperties limits,
               Clock clock) {
        this.userAccounts = userAccounts;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwords = passwords;
        this.db = db;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
        this.clock = clock;
    }

    public Mono<RegisterResponse> register(RegisterRequest request, String clientIp) {
        return Mono.defer(() -> {
            limit("register:ip:" + clientIp, limits.registerPerIp());
            String displayName = PlainText.normalize("displayName", request.displayName(), 1, 80);
            PasswordHashing.checkPolicy(request.password());
            String email = EmailAddresses.trim(request.email());
            String normalizedEmail = EmailAddresses.normalize(request.email());
            return passwords.hash(request.password())
                    .flatMap(hash -> db.call(() -> userAccounts.register(
                            new NewUserAccount(email, normalizedEmail, hash, displayName), now())))
                    .map(profile -> {
                        log.info("User registered: userId={}", profile.id());
                        return new RegisterResponse(profile.id().toString());
                    });
        });
    }

    public Mono<LoginResponse> login(LoginRequest request, String clientIp) {
        return Mono.defer(() -> {
            String normalizedEmail = EmailAddresses.normalize(request.email());
            limit("login:ip:" + clientIp, limits.loginPerIp());
            limit("login:email:" + normalizedEmail, limits.loginPerEmail());
            return db.call(() -> userAccounts.findCredentials(normalizedEmail))
                    .flatMap(credentials -> passwords
                            .matches(request.password(), credentials.map(UserCredentials::passwordHash).orElse(null))
                            .flatMap(matches -> {
                                if (!matches || credentials.isEmpty()) {
                                    log.info("Login failed");
                                    return Mono.error(new ApiException(ErrorCode.INVALID_CREDENTIALS,
                                            "Invalid email or password."));
                                }
                                return startSession(credentials.get().userId());
                            }));
        });
    }

    private Mono<LoginResponse> startSession(UUID userId) {
        return db.call(() -> {
            Instant now = now();
            UserProfile profile = userAccounts.findProfile(userId)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password."));
            IssuedRefreshToken refresh = refreshTokens.startFamily(userId, now);
            IssuedAccessToken access = accessTokens.issue(userId, profile.systemRole());
            log.info("Login succeeded: userId={} familyId={}", userId, refresh.familyId());
            return new LoginResponse(access.value(), access.expiresAt(), refresh.rawValue(), refresh.expiresAt(),
                    profile);
        });
    }

    public Mono<TokenPairResponse> refresh(RefreshRequest request) {
        return db.call(() -> refreshTokens.refresh(request.refreshToken(), now()))
                .onErrorMap(AuthFacade::isPersistenceFailure, AuthFacade::serviceBusy)
                .flatMap(outcome -> switch (outcome) {
                    case RefreshOutcome.Rotated rotated -> {
                        IssuedAccessToken access = accessTokens.issue(rotated.userId(), rotated.systemRole());
                        IssuedRefreshToken next = rotated.refreshToken();
                        yield Mono.just(new TokenPairResponse(access.value(), access.expiresAt(), next.rawValue(),
                                next.expiresAt()));
                    }
                    case RefreshOutcome.ReuseDetected reuse -> Mono.error(new ApiException(
                            ErrorCode.REFRESH_TOKEN_REUSED, "Refresh token reuse detected; the session was revoked."));
                    case RefreshOutcome.Invalid invalid -> Mono.error(new ApiException(
                            ErrorCode.REFRESH_TOKEN_INVALID, "Refresh token is invalid or expired."));
                });
    }

    public Mono<Void> logout(AuthenticatedUser caller, RefreshRequest request) {
        return db.call(() -> refreshTokens.logout(caller.userId(), request.refreshToken(), now()))
                .onErrorMap(AuthFacade::isPersistenceFailure, AuthFacade::serviceBusy)
                .flatMap(outcome -> outcome == LogoutOutcome.LOGGED_OUT
                        ? Mono.<Void>empty()
                        : Mono.error(new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
                                "Refresh token is invalid or expired.")));
    }

    private void limit(String key, AuthRateLimitProperties.Limit limit) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(key, limit.limit(), limit.window());
        if (!decision.allowed()) {
            throw ApiException.rateLimited(decision.retryAfterSeconds());
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isPersistenceFailure(Throwable error) {
        return error instanceof DataAccessException || error instanceof TransactionException
                || error instanceof PersistenceException;
    }

    /** A failed refresh/revocation is never reported as 401 (SYSTEM_DESIGN §8.2 rule 4). */
    private static ApiException serviceBusy(Throwable error) {
        log.error("Refresh-token transaction failed and was rolled back: {}", error.getClass().getSimpleName());
        return new ApiException(ErrorCode.SERVICE_BUSY, "The service is busy. Retry shortly.");
    }
}
