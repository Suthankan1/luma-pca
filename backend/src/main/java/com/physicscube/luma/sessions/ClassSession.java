package com.physicscube.luma.sessions;

import com.physicscube.luma.shared.time.DbTime;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One scheduled class. Several batches may share it; the batch set is part of the
 * entity, so changing it advances the version like any other edit.
 */
@Entity
@Table(name = "class_sessions")
class ClassSession {
    @Id
    private UUID id;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private UUID academyId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "zoom_webinar_id", length = 20)
    private String zoomWebinarId;

    @Column(name = "series_id", updatable = false)
    private UUID seriesId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_batches", joinColumns = @JoinColumn(name = "session_id"))
    private Set<SessionBatch> batches = new HashSet<>();

    protected ClassSession() {
    }

    ClassSession(UUID academyId, String title, Instant startsAt, Instant endsAt, ZoneId zone, Set<UUID> batchIds,
                 String zoomWebinarId) {
        this.id = UUID.randomUUID();
        this.academyId = academyId;
        this.status = "SCHEDULED";
        update(title, startsAt, endsAt, zone, batchIds, "SCHEDULED", zoomWebinarId);
    }

    /** One class of a weekly series created together. */
    ClassSession(UUID academyId, String title, Instant startsAt, Instant endsAt, ZoneId zone, Set<UUID> batchIds,
                 String zoomWebinarId, UUID seriesId) {
        this(academyId, title, startsAt, endsAt, zone, batchIds, zoomWebinarId);
        this.seriesId = seriesId;
    }

    UUID getSeriesId() {
        return seriesId;
    }

    void cancel() {
        this.status = "CANCELLED";
    }

    void update(String title, Instant startsAt, Instant endsAt, ZoneId zone, Set<UUID> batchIds, String status,
                String zoomWebinarId) {
        this.zoomWebinarId = zoomWebinarId;
        this.title = title;
        this.startsAt = DbTime.truncate(startsAt);
        this.endsAt = DbTime.truncate(endsAt);
        this.timeZone = zone.getId();
        this.status = status;
        Set<SessionBatch> wanted = batchIds.stream().map(id -> new SessionBatch(id, academyId)).collect(Collectors.toSet());
        batches.retainAll(wanted);
        batches.addAll(wanted);
    }

    /** The calendar date of the class where it is held. */
    LocalDate localDate() {
        return startsAt.atZone(ZoneId.of(timeZone)).toLocalDate();
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

    String getTitle() {
        return title;
    }

    Instant getStartsAt() {
        return startsAt;
    }

    Instant getEndsAt() {
        return endsAt;
    }

    String getTimeZone() {
        return timeZone;
    }

    String getZoomWebinarId() {
        return zoomWebinarId;
    }

    String getStatus() {
        return status;
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

    Set<UUID> batchIds() {
        return batches.stream().map(SessionBatch::getBatchId).collect(Collectors.toUnmodifiableSet());
    }
}
