package com.physicscube.luma.sessions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** @param batches only the batches the caller may see; staff never learn about other batches' participation */
public record SessionView(UUID id, String title, Instant startsAt, Instant endsAt, String timeZone, LocalDate localDate,
                          String status, String zoomWebinarId, List<Batch> batches, long version, Instant createdAt, Instant updatedAt) {
    public record Batch(UUID batchId, UUID courseId, String code, String name) {
    }
}
