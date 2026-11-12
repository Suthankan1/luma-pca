package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.PasswordReset;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {
    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /** Requests (used, revoked or open) created since the given time, for rate limiting. */
    @Query("select count(r) from PasswordReset r where r.accountId = :accountId and r.createdAt > :since")
    long countSince(@Param("accountId") UUID accountId, @Param("since") Instant since);

    /** Atomic single-use claim: returns 1 only for the one caller that consumes a usable reset. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PasswordReset r set r.consumedAt = :now
            where r.tokenHash = :tokenHash and r.consumedAt is null and r.revokedAt is null and r.expiresAt > :now
            """)
    int consume(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PasswordReset r set r.revokedAt = :now
            where r.accountId = :accountId and r.consumedAt is null and r.revokedAt is null
            """)
    int revokeOpen(@Param("accountId") UUID accountId, @Param("now") Instant now);
}
