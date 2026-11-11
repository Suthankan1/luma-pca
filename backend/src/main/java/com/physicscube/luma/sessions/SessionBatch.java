package com.physicscube.luma.sessions;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import java.util.UUID;

/** One batch taking part in a session. The academy column feeds the same-academy foreign keys. */
@Embeddable
class SessionBatch {
    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "academy_id", nullable = false)
    private UUID academyId;

    protected SessionBatch() {
    }

    SessionBatch(UUID batchId, UUID academyId) {
        this.batchId = batchId;
        this.academyId = academyId;
    }

    UUID getBatchId() {
        return batchId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionBatch that && batchId.equals(that.batchId) && academyId.equals(that.academyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchId, academyId);
    }
}
