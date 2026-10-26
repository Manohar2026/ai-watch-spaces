package com.aiwatchspaces.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aiwatchspaces.common.security.SystemRole;
import com.aiwatchspaces.users.UserAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token rotation, replay detection and logout (SYSTEM_DESIGN §8.2, Appendix E #5–#7).
 * Blocking and transactional: call only on the {@code db-*} executor. Methods return outcome values;
 * they throw only for genuine failures, which roll back.
 */
@Service
class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repository;
    private final UserAccounts userAccounts;
    private final AuthProperties properties;

    RefreshTokenService(RefreshTokenRepository repository, UserAccounts userAccounts, AuthProperties properties) {
        this.repository = repository;
        this.userAccounts = userAccounts;
        this.properties = properties;
    }

    /** Starts a new token family (every login). */
    @Transactional
    IssuedRefreshToken startFamily(UUID userId, Instant now) {
        return issue(userId, UUID.randomUUID(), now);
    }

    /**
     * Order of checks (SYSTEM_DESIGN §8.2): unknown → Invalid; revoked (expired or not) → replay, family
     * revoked and committed; not revoked but expired → Invalid with no change; otherwise rotate.
     */
    @Transactional
    RefreshOutcome refresh(String rawToken, Instant now) {
        Optional<RefreshToken> found = repository.lockByTokenHash(RefreshTokenCodec.hash(rawToken));
        if (found.isEmpty()) {
            return new RefreshOutcome.Invalid();
        }
        RefreshToken presented = found.get();
        if (presented.isRevoked()) {
            int revoked = repository.revokeActiveInFamily(presented.familyId(), RevokedReason.REUSE_DETECTED, now);
            log.warn("Refresh token reuse detected: familyId={} userId={} revokedTokens={}",
                    presented.familyId(), presented.userId(), revoked);
            return new RefreshOutcome.ReuseDetected(presented.familyId(), presented.userId(), revoked);
        }
        if (presented.isExpiredAt(now)) {
            return new RefreshOutcome.Invalid();
        }
        Optional<SystemRole> role = userAccounts.findSystemRole(presented.userId());
        if (role.isEmpty()) {
            return new RefreshOutcome.Invalid();
        }
        IssuedRefreshToken next = issue(presented.userId(), presented.familyId(), now);
        presented.rotateTo(next.tokenId(), now);
        repository.saveAndFlush(presented);
        return new RefreshOutcome.Rotated(presented.userId(), role.get(), next);
    }

    /**
     * Public logout (SYSTEM_DESIGN §8.2): the refresh token is the credential. Revokes every still-active
     * token of its family; idempotent for already revoked or expired tokens.
     */
    @Transactional
    LogoutOutcome logout(String rawToken, Instant now) {
        Optional<RefreshToken> found = repository.lockByTokenHash(RefreshTokenCodec.hash(rawToken));
        if (found.isEmpty()) {
            return LogoutOutcome.INVALID;
        }
        repository.revokeActiveInFamily(found.get().familyId(), RevokedReason.LOGOUT, now);
        return LogoutOutcome.LOGGED_OUT;
    }

    private IssuedRefreshToken issue(UUID userId, UUID familyId, Instant now) {
        String raw = RefreshTokenCodec.newRawToken();
        Instant expiresAt = now.plus(properties.refreshTokenTtl());
        RefreshToken token = new RefreshToken(UUID.randomUUID(), userId, familyId, RefreshTokenCodec.hash(raw),
                now, expiresAt);
        repository.saveAndFlush(token);
        return new IssuedRefreshToken(token.id(), familyId, raw, expiresAt);
    }
}
