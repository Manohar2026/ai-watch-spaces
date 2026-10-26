package com.aiwatchspaces.users;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aiwatchspaces.common.security.SystemRole;

/**
 * Public application service of the {@code users} module. Blocking: call only on the {@code db-*}
 * executor. Each method runs in (or joins) a transaction and returns DTOs, never entities.
 */
public interface UserAccounts {

    /** Creates a USER account. Throws {@code 409 EMAIL_TAKEN} if the normalized email exists. */
    UserProfile register(NewUserAccount account, Instant now);

    Optional<UserCredentials> findCredentials(String normalizedEmail);

    Optional<UserProfile> findProfile(UUID userId);

    /** The current global role, re-read from the database (SYSTEM_DESIGN §8.1). */
    Optional<SystemRole> findSystemRole(UUID userId);

    /** Applies non-null changes. Throws {@code 404 NOT_FOUND} if the user does not exist. */
    UserProfile updateProfile(UUID userId, ProfileChanges changes, Instant now);
}
