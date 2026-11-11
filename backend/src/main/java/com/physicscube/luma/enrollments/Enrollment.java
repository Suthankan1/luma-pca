package com.physicscube.luma.enrollments;

import com.physicscube.luma.shared.time.DbTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A student's membership of one batch. Withdrawal sets a date; the row is kept. */
@Entity
@Table(name = "enrollments")
class Enrollment {
    @Id
    private UUID id;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private UUID academyId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "enrolled_on", nullable = false)
    private LocalDate enrolledOn;

    @Column(name = "withdrawn_on")
    private LocalDate withdrawnOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Enrollment() {
    }

    Enrollment(UUID academyId, UUID batchId, UUID studentId, LocalDate enrolledOn) {
        this.id = UUID.randomUUID();
        this.academyId = academyId;
        this.batchId = batchId;
        this.studentId = studentId;
        this.enrolledOn = enrolledOn;
    }

    void update(LocalDate enrolledOn, LocalDate withdrawnOn) {
        this.enrolledOn = enrolledOn;
        this.withdrawnOn = withdrawnOn;
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

    UUID getAcademyId() {
        return academyId;
    }

    UUID getBatchId() {
        return batchId;
    }

    UUID getStudentId() {
        return studentId;
    }

    LocalDate getEnrolledOn() {
        return enrolledOn;
    }

    LocalDate getWithdrawnOn() {
        return withdrawnOn;
    }

    Long getVersion() {
        return version;
    }
}
