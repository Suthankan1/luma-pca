package com.physicscube.luma.batches;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BatchView(UUID id, UUID courseId, String code, String name, LocalDate startsOn, LocalDate endsOn,
                        boolean active, long version, Instant createdAt, Instant updatedAt) {
    static BatchView of(Batch batch) {
        return new BatchView(batch.getId(), batch.getCourseId(), batch.getCode(), batch.getName(), batch.getStartsOn(),
                batch.getEndsOn(), batch.isActive(), batch.getVersion(), batch.getCreatedAt(), batch.getUpdatedAt());
    }
}
