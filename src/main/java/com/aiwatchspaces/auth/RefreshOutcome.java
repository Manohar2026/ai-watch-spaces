package com.aiwatchspaces.auth;

import java.util.UUID;

import com.aiwatchspaces.common.security.SystemRole;

/**
 * Result of {@link RefreshTokenService#refresh}. Replay is an outcome, not an exception, so the
 * family revocation commits before the adapter returns {@code 401 REFRESH_TOKEN_REUSED}
 * (ADR-003 §4, SYSTEM_DESIGN §8.2).
 */
sealed interface RefreshOutcome {

    record Rotated(UUID userId, SystemRole systemRole, IssuedRefreshToken refreshToken) implements RefreshOutcome {
    }

    record ReuseDetected(UUID familyId, UUID userId, int revokedCount) implements RefreshOutcome {
    }

    record Invalid() implements RefreshOutcome {
    }
}
