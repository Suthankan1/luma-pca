package com.physicscube.luma.attendance.domain;

import java.time.Instant;
import java.util.Objects;

/** A single observed connection window. Domain does not infer attention. */
public record ConnectionInterval(Instant joinedAt, Instant leftAt) {
    public ConnectionInterval {
        Objects.requireNonNull(joinedAt, "joinedAt is required");
        Objects.requireNonNull(leftAt, "leftAt is required");
        if (!leftAt.isAfter(joinedAt)) {
            throw new IllegalArgumentException("leftAt must be after joinedAt");
        }
    }
}
