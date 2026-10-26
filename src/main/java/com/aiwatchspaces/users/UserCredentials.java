package com.aiwatchspaces.users;

import java.util.UUID;

import com.aiwatchspaces.common.security.SystemRole;

/** Login lookup result. The hash never leaves the auth flow and is never logged. */
public record UserCredentials(UUID userId, String passwordHash, SystemRole systemRole) {

    @Override
    public String toString() {
        return "UserCredentials[userId=" + userId + ", systemRole=" + systemRole + "]";
    }
}
