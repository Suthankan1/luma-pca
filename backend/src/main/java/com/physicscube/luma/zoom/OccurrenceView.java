package com.physicscube.luma.zoom;

import java.time.Instant;
import java.util.UUID;

/**
 * A Zoom webinar occurrence seen in webhook events and its link decision.
 *
 * @param classSessionId {@code null} when undecided, or when an administrator marked it "not a class"
 * @param linkSource {@code null} (undecided), AUTOMATIC or MANUAL
 * @param ended a webinar.ended event has arrived
 * @param reportStatus {@code null} (not fetched), FETCHED or FAILED: Zoom's participant report (004B)
 */
public record OccurrenceView(String occurrenceUuid, String webinarId, Instant firstEventAt, Instant lastEventAt,
                             int eventCount, UUID classSessionId, String linkSource, Instant linkedAt, boolean ended,
                             String reportStatus, Instant reportFetchedAt, Integer reportJoinSessions) {
}
