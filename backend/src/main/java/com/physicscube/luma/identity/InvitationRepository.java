package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Invitation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByTokenHash(String tokenHash);

    /** Atomic single-use claim: returns 1 only for the one caller that consumes a usable invitation. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Invitation i set i.consumedAt = :now
            where i.tokenHash = :tokenHash and i.consumedAt is null and i.revokedAt is null and i.expiresAt > :now
            """)
    int consume(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Invitation i set i.revokedAt = :now
            where i.accountId = :accountId and i.consumedAt is null and i.revokedAt is null
            """)
    int revokeOpen(@Param("accountId") UUID accountId, @Param("now") Instant now);
}
