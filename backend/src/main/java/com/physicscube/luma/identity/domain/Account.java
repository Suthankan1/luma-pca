package com.physicscube.luma.identity.domain;

import com.physicscube.luma.shared.time.DbTime;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One sign-in identity per person; never duplicated per batch or course. */
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private UUID academyId;

    @Column(nullable = false, length = EmailAddress.MAX_LENGTH)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountStatus status;

    @Column(name = "auth_version", nullable = false)
    private int authVersion;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "account_roles", joinColumns = @JoinColumn(name = "account_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    protected Account() {
    }

    public static Account pending(UUID academyId, String normalizedEmail, Set<Role> roles) {
        if (academyId == null) {
            throw new IllegalArgumentException("Academy is required");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        Account account = new Account();
        account.id = UUID.randomUUID();
        account.academyId = academyId;
        account.email = normalizedEmail;
        account.status = AccountStatus.PENDING_ACTIVATION;
        account.roles = EnumSet.copyOf(roles);
        return account;
    }

    /** First call activates a pending account; later calls are password changes. */
    public void applyPasswordHash(String encodedHash, Instant now) {
        if (status == AccountStatus.DISABLED) {
            throw new IllegalStateException("A disabled account cannot set a password");
        }
        if (encodedHash == null || encodedHash.isBlank()) {
            throw new IllegalArgumentException("Password hash is required");
        }
        passwordHash = encodedHash;
        passwordChangedAt = now;
        status = AccountStatus.ACTIVE;
        failedLoginCount = 0;
        lockedUntil = null;
        authVersion++;
    }

    /** Replaces every role grant. Callers enforce who may grant which role. */
    public void replaceRoles(Set<Role> newRoles) {
        if (newRoles == null || newRoles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        roles.clear();
        roles.addAll(newRoles);
    }

    public void disable() {
        if (status != AccountStatus.DISABLED) {
            status = AccountStatus.DISABLED;
            authVersion++;
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = DbTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = DbTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAcademyId() {
        return academyId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public int getAuthVersion() {
        return authVersion;
    }

    public Long getVersion() {
        return version;
    }

    public java.time.Instant getCreatedAt() {
        return createdAt;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }
}
