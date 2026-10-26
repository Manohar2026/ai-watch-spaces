package com.aiwatchspaces.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** {@code SELECT … FOR UPDATE}: serializes concurrent use of one token (SYSTEM_DESIGN §8.2). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> lockByTokenHash(@Param("tokenHash") String tokenHash);

    /** Revokes every still-active token of a family. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason
            where t.familyId = :familyId and t.revokedAt is null""")
    int revokeActiveInFamily(@Param("familyId") UUID familyId, @Param("reason") RevokedReason reason,
                             @Param("now") Instant now);
}
