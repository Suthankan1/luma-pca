package com.physicscube.luma.sessions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A class as its student sees it: only the student's own batches, no other batches or students.
 *
 * @param origin  LUMA (scheduled before Zoom owned schedules) or ZOOM_RUN (an actual Zoom run: while it is live its end is
 *                only the class-length limit, so screens show it as live rather than as a time range)
 */
public record StudentClassView(UUID id, String title, Instant startsAt, Instant endsAt, String timeZone, LocalDate localDate,
                               String status, List<Batch> batches, String origin) {

    public record Batch(UUID batchId, String code, String name) {
    }
}
