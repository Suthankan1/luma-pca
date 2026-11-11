package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Internal to the identity module; other modules go through {@link AccountService}. */
interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockById(UUID id);

    /**
     * Counts one sign-in attempt, atomically. Returns 0 when the account is locked or
     * not active, so the caller must not check the password. Reaching the limit sets
     * the lock; the first attempt after a lock has expired starts a new count.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE accounts SET
              failed_login_count = CASE WHEN locked_until IS NOT NULL THEN 1 ELSE failed_login_count + 1 END,
              locked_until = CASE WHEN locked_until IS NULL AND failed_login_count + 1 >= :maxFailures
                                  THEN CAST(:lockUntil AS timestamptz) ELSE NULL END,
              updated_at = :now
            WHERE id = :id AND status = 'ACTIVE' AND (locked_until IS NULL OR locked_until <= :now)
            """)
    int reserveLoginAttempt(@Param("id") UUID id, @Param("maxFailures") int maxFailures,
                            @Param("now") Instant now, @Param("lockUntil") Instant lockUntil);

    /** Clears the counter and any lock, unless the account changed since the password was read. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE accounts SET failed_login_count = 0, locked_until = NULL
            WHERE id = :id AND status = 'ACTIVE' AND auth_version = :authVersion
            """)
    int recordLoginSuccess(@Param("id") UUID id, @Param("authVersion") int authVersion);

    @Query("select count(a) > 0 from Account a where a.id = :id and a.lockedUntil > :now")
    boolean isLocked(@Param("id") UUID id, @Param("now") Instant now);
}
