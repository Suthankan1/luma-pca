package com.physicscube.luma.students;

import com.physicscube.luma.shared.time.DbTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** One academy identity per student, linked to exactly one sign-in account. */
@Entity
@Table(name = "students")
class Student {
    @Id
    private UUID id;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private UUID academyId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "student_number", nullable = false, updatable = false, length = 60)
    private String studentNumber;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Student() {
    }

    Student(UUID academyId, UUID accountId, String studentNumber, String fullName) {
        this.id = UUID.randomUUID();
        this.academyId = academyId;
        this.accountId = accountId;
        this.studentNumber = studentNumber;
        this.fullName = fullName;
        this.active = true;
    }

    void update(String fullName, boolean active) {
        this.fullName = fullName;
        this.active = active;
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

    UUID getId() {
        return id;
    }

    UUID getAccountId() {
        return accountId;
    }

    String getStudentNumber() {
        return studentNumber;
    }

    String getFullName() {
        return fullName;
    }

    boolean isActive() {
        return active;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    Long getVersion() {
        return version;
    }
}
