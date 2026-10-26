package com.aiwatchspaces.auth;

import java.time.Instant;
import java.util.UUID;

/** A newly issued refresh token. {@code rawValue} is returned to the client once and never stored or logged. */
record IssuedRefreshToken(UUID tokenId, UUID familyId, String rawValue, Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedRefreshToken[tokenId=" + tokenId + ", familyId=" + familyId + ", expiresAt=" + expiresAt
                + ", rawValue=<redacted>]";
    }
}
