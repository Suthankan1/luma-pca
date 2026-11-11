package com.physicscube.luma.batches;

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

/** A group of students taking one course together. It can never move to another course. */
@Entity
@Table(name = "batches")
class Batch {
    @Id
    private UUID id;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private UUID academyId;

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Batch() {
    }

    Batch(UUID academyId, UUID courseId, String code, String name, LocalDate startsOn, LocalDate endsOn) {
        this.id = UUID.randomUUID();
        this.academyId = academyId;
        this.courseId = courseId;
        this.active = true;
        apply(code, name, startsOn, endsOn);
    }

    void update(String code, String name, LocalDate startsOn, LocalDate endsOn, boolean active) {
        apply(code, name, startsOn, endsOn);
        this.active = active;
    }

    private void apply(String code, String name, LocalDate startsOn, LocalDate endsOn) {
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("A batch cannot end before it starts");
        }
        this.code = code;
        this.name = name;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
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

    UUID getCourseId() {
        return courseId;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    LocalDate getStartsOn() {
        return startsOn;
    }

    LocalDate getEndsOn() {
        return endsOn;
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
