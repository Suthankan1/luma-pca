package com.physicscube.luma.shared.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Timestamps at the precision PostgreSQL stores (microseconds). Some platforms'
 * clocks report nanoseconds; without this a value returned straight after a write
 * would differ from the same value read back later.
 */
public final class DbTime {
    private DbTime() {
    }

    public static Instant now() {
        return truncate(Instant.now());
    }

    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
