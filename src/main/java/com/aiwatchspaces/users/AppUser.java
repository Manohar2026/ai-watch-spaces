package com.aiwatchspaces.users;

import java.time.Instant;
import java.util.UUID;

import com.aiwatchspaces.common.security.SystemRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code app_users}. Package-private: other modules use {@link UserAccounts}. */
@Entity
@Table(name = "app_users")
class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "normalized_email", nullable = false, length = 320, unique = true)
    private String normalizedEmail;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", nullable = false, length = 16)
    private SystemRole systemRole;

    @Column(name = "subtitle_locale", nullable = false, length = 16)
    private String subtitleLocale;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() {
    }

    AppUser(UUID id, String email, String normalizedEmail, String passwordHash, String displayName,
            String subtitleLocale, Instant now) {
        this.id = id;
        this.email = email;
        this.normalizedEmail = normalizedEmail;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.systemRole = SystemRole.USER;
        this.subtitleLocale = subtitleLocale;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void changeDisplayName(String displayName, Instant now) {
        this.displayName = displayName;
        this.updatedAt = now;
    }

    void changeSubtitleLocale(String subtitleLocale, Instant now) {
        this.subtitleLocale = subtitleLocale;
        this.updatedAt = now;
    }

    UserProfile toProfile() {
        return new UserProfile(id, email, displayName, systemRole, subtitleLocale, createdAt, updatedAt);
    }

    UserCredentials toCredentials() {
        return new UserCredentials(id, passwordHash, systemRole);
    }

    SystemRole systemRole() {
        return systemRole;
    }
}
