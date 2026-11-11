package com.physicscube.luma.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Activation invitation. Holds only the digest of its token. */
@Entity
@Table(name = "account_invitations")
public class Invitation {
    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Invitation() {
    }

    public static Invitation issue(UUID accountId, String tokenHash, UUID createdBy, Instant now, Duration ttl) {
        Invitation invitation = new Invitation();
        invitation.id = UUID.randomUUID();
        invitation.accountId = accountId;
        invitation.tokenHash = tokenHash;
        invitation.createdBy = createdBy;
        invitation.createdAt = now;
        invitation.expiresAt = now.plus(ttl);
        return invitation;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
