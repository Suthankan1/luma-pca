package com.physicscube.luma.shared.ratelimit;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixed-window counters in PostgreSQL, so every API instance counts together. */
@Component
class RateLimitCounters {
    private final JdbcTemplate jdbc;

    RateLimitCounters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Windows are aligned to the epoch, so all instances agree where one ends. */
    static Instant windowStart(Instant now, Duration window) {
        long size = window.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), size) * size);
    }

    /** Counts one request atomically and returns the count in the current window, this one included. */
    int hit(String bucket, String client, Instant windowStart) {
        Integer hits = jdbc.queryForObject("""
                INSERT INTO rate_limit_counters (bucket, client, window_start, hits) VALUES (?, ?, ?, 1)
                ON CONFLICT (bucket, client, window_start) DO UPDATE SET hits = rate_limit_counters.hits + 1
                RETURNING hits
                """, Integer.class, bucket, client, Timestamp.from(windowStart));
        return hits == null ? 0 : hits;
    }

    /** Removes windows that ended long ago; the longest allowed window is one day. */
    int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM rate_limit_counters WHERE window_start < ?", Timestamp.from(cutoff));
    }
}
