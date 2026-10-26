package com.aiwatchspaces.auth;

import java.time.Instant;

record IssuedAccessToken(String value, Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedAccessToken[expiresAt=" + expiresAt + ", value=<redacted>]";
    }
}
