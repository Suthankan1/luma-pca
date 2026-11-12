package com.physicscube.luma.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A self-service password reset. Holds only the digest of its emailed token. */
@Entity
@Table(name = "password_resets")
public class PasswordReset {
    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PasswordReset() {
    }

    public static PasswordReset issue(UUID accountId, String tokenHash, Instant now, Duration ttl) {
        PasswordReset reset = new PasswordReset();
        reset.id = UUID.randomUUID();
        reset.accountId = accountId;
        reset.tokenHash = tokenHash;
        reset.createdAt = now;
        reset.expiresAt = now.plus(ttl);
        return reset;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
