package com.aiwatchspaces.common.security;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The caller, resolved from the JWT at the web edge and passed explicitly into blocking services
 * (ADR-003 §5). Services never read the security context themselves.
 */
public record AuthenticatedUser(UUID userId, SystemRole systemRole, Instant tokenExpiresAt) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(systemRole, "systemRole");
        Objects.requireNonNull(tokenExpiresAt, "tokenExpiresAt");
    }
}
