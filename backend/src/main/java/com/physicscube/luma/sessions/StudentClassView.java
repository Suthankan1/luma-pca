package com.physicscube.luma.sessions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A class as its student sees it: only the student's own batches, and no Zoom webinar ID,
 * other batches or other students.
 */
public record StudentClassView(UUID id, String title, Instant startsAt, Instant endsAt, String timeZone, LocalDate localDate,
                               String status, List<Batch> batches) {
    public record Batch(UUID batchId, String code, String name) {
    }
}
