package com.physicscube.luma.batches;

import com.physicscube.luma.shared.time.DbTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Grants one staff account access to one batch in one role. Removing it revokes the access. */
@Entity
@Table(name = "staff_assignments")
class StaffAssignment {
    @Id
    private UUID id;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private UUID academyId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(nullable = false, updatable = false, length = 30)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected StaffAssignment() {
    }

    StaffAssignment(UUID academyId, UUID accountId, UUID batchId, String role, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.academyId = academyId;
        this.accountId = accountId;
        this.batchId = batchId;
        this.role = role;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = DbTime.now();
    }

    UUID getId() {
        return id;
    }

    UUID getAccountId() {
        return accountId;
    }

    UUID getBatchId() {
        return batchId;
    }

    String getRole() {
        return role;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
