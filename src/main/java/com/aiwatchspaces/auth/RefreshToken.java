package com.aiwatchspaces.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code refresh_tokens}. Package-private to the auth module. */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 24)
    private RevokedReason revokedReason;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    protected RefreshToken() {
    }

    RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    void rotateTo(UUID replacementId, Instant now) {
        this.revokedAt = now;
        this.revokedReason = RevokedReason.ROTATED;
        this.replacedByTokenId = replacementId;
    }

    UUID id() {
        return id;
    }

    UUID userId() {
        return userId;
    }

    UUID familyId() {
        return familyId;
    }

    Instant expiresAt() {
        return expiresAt;
    }
}
