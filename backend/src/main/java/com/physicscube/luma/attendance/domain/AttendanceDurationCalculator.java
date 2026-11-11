package com.physicscube.luma.attendance.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Calculates union of verified connection intervals, clipped to scheduled session.
 * This is a domain primitive only: webhook identity, replay and report reconciliation
 * must be implemented in later vertical slices before use with real students.
 */
public final class AttendanceDurationCalculator {
    private AttendanceDurationCalculator() {}

    public static Duration connectedDuration(
            Instant scheduledStart, Instant scheduledEnd, List<ConnectionInterval> observations) {
        Objects.requireNonNull(scheduledStart, "scheduledStart");
        Objects.requireNonNull(scheduledEnd, "scheduledEnd");
        Objects.requireNonNull(observations, "observations");
        if (!scheduledEnd.isAfter(scheduledStart)) {
            throw new IllegalArgumentException("scheduledEnd must be after scheduledStart");
        }
        var clipped = new ArrayList<ConnectionInterval>();
        for (var segment : observations) {
            Objects.requireNonNull(segment, "segment");
            var from = segment.joinedAt().isBefore(scheduledStart) ? scheduledStart : segment.joinedAt();
            var to = segment.leftAt().isAfter(scheduledEnd) ? scheduledEnd : segment.leftAt();
            if (to.isAfter(from)) clipped.add(new ConnectionInterval(from, to));
        }
        clipped.sort(Comparator.comparing(ConnectionInterval::joinedAt));
        Duration result = Duration.ZERO;
        Instant currentStart = null;
        Instant currentEnd = null;
        for (var segment : clipped) {
            if (currentStart == null) {
                currentStart = segment.joinedAt();
                currentEnd = segment.leftAt();
            } else if (!segment.joinedAt().isAfter(currentEnd)) {
                if (segment.leftAt().isAfter(currentEnd)) currentEnd = segment.leftAt();
            } else {
                result = result.plus(Duration.between(currentStart, currentEnd));
                currentStart = segment.joinedAt();
                currentEnd = segment.leftAt();
            }
        }
        if (currentStart != null) result = result.plus(Duration.between(currentStart, currentEnd));
        return result;
    }
}
