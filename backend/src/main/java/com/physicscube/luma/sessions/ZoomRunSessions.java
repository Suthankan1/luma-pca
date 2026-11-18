package com.physicscube.luma.sessions;

import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Classes created by an actual Zoom webinar run (origin ZOOM_RUN), for the Zoom module only: no caller, no audit of a
 * person. Their times follow the run: they start when Zoom says the webinar actually started and, while it runs, end at
 * the longest a class may last ({@link #LIVE_SPAN}) so no attendance is cut off; when it ends they end then. Classes staff
 * scheduled (origin LUMA) are never changed here.
 */
@Component
public class ZoomRunSessions {
    /** The class-length limit (class_sessions check): a live run's class ends here until Zoom says it ended. */
    public static final Duration LIVE_SPAN = Duration.ofHours(12);

    private final JdbcTemplate jdbc;

    ZoomRunSessions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID create(UUID academyId, String title, Instant startsAt, String timeZone, String webinarId, Collection<UUID> batchIds) {
        UUID id = UUID.randomUUID();
        Instant now = DbTime.now();
        jdbc.update("""
                INSERT INTO class_sessions (id, academy_id, title, starts_at, ends_at, time_zone, status, zoom_webinar_id, origin,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SCHEDULED', ?, 'ZOOM_RUN', ?, ?)
                """, id, academyId, title(title), Timestamp.from(startsAt), Timestamp.from(startsAt.plus(LIVE_SPAN)), timeZone,
                webinarId, Timestamp.from(now), Timestamp.from(now));
        for (UUID batchId : batchIds) {
            jdbc.update("INSERT INTO session_batches (session_id, batch_id, academy_id) VALUES (?, ?, ?)", id, batchId, academyId);
        }
        return id;
    }

    /**
     * Sets the run's class to its actual times; {@code endsAt} is {@code null} while the webinar runs. Times are kept
     * within the class rules (end after start, at most {@link #LIVE_SPAN}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void retime(UUID academyId, UUID sessionId, Instant startsAt, Instant endsAt) {
        Instant end = endsAt == null || endsAt.isAfter(startsAt.plus(LIVE_SPAN)) ? startsAt.plus(LIVE_SPAN)
                : endsAt.isAfter(startsAt) ? endsAt : startsAt.plusSeconds(60);
        jdbc.update("""
                UPDATE class_sessions SET starts_at = ?, ends_at = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND academy_id = ? AND origin = 'ZOOM_RUN'
                """, Timestamp.from(startsAt), Timestamp.from(end), Timestamp.from(DbTime.now()), sessionId, academyId);
    }

    private static String title(String topic) {
        String text = topic == null || topic.isBlank() ? "Zoom webinar" : topic.strip();
        return text.length() <= 200 ? text : text.substring(0, 200).strip();
    }
}
