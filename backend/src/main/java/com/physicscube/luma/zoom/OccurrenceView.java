package com.physicscube.luma.zoom;

import java.time.Instant;
import java.util.UUID;

/**
 * A Zoom webinar occurrence seen in webhook events and its link decision.
 *
 * @param classSessionId {@code null} when undecided, or when an administrator marked it "not a class"
 * @param linkSource {@code null} (undecided), AUTOMATIC or MANUAL
 */
public record OccurrenceView(String occurrenceUuid, String webinarId, Instant firstEventAt, Instant lastEventAt,
                             int eventCount, UUID classSessionId, String linkSource, Instant linkedAt) {
}
