package com.physicscube.luma.sessions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A class as its student sees it: only the student's own batches, no other batches or students.
 *
 * @param joinUrl Zoom's public join address for the class's webinar (the Academy asked for joining links on the student
 *                dashboard); {@code null} when the class is cancelled or has no webinar. Zoom still applies the webinar's own
 *                registration and passcode settings.
 * @param origin  LUMA (scheduled before Zoom owned schedules) or ZOOM_RUN (an actual Zoom run: while it is live its end is
 *                only the class-length limit, so screens show it as live rather than as a time range)
 */
public record StudentClassView(UUID id, String title, Instant startsAt, Instant endsAt, String timeZone, LocalDate localDate,
                               String status, List<Batch> batches, String joinUrl, String origin) {
    static String joinUrl(String webinarId, String status) {
        return webinarId == null || !"SCHEDULED".equals(status) ? null : "https://zoom.us/j/" + webinarId.replaceAll("\\D", "");
    }

    public record Batch(UUID batchId, String code, String name) {
    }
}
