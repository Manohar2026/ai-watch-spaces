package com.aiwatchspaces.users;

import java.time.Instant;
import java.util.UUID;

import com.aiwatchspaces.common.security.SystemRole;

/** Public profile view of a user. */
public record UserProfile(UUID id, String email, String displayName, SystemRole systemRole, String subtitleLocale,
                          Instant createdAt, Instant updatedAt) {
}
