package com.physicscube.luma.zoom;

import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Links a Zoom webinar occurrence to the class session it belongs to, automatically
 * and only when unambiguous: exactly one SCHEDULED session on the same webinar ID
 * whose time, widened by {@link #SLACK} on both sides, contains the event. No match or
 * several matches leave the occurrence for an administrator. An existing decision,
 * automatic or manual, is never changed here.
 */
@Component
class OccurrenceLinker {
    /** Hosts often open a webinar early or let it run over. */
    static final Duration SLACK = Duration.ofMinutes(60);
    private static final Logger log = LoggerFactory.getLogger(OccurrenceLinker.class);

    enum Result { LINKED, ALREADY_DECIDED, NO_MATCH, AMBIGUOUS }

    private final JdbcTemplate jdbc;
    private final ClassSessionService sessions;

    OccurrenceLinker(JdbcTemplate jdbc, ClassSessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    Result tryLink(UUID academyId, String occurrenceUuid, String webinarId, Instant eventTime) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM zoom_occurrence_links "
                + "WHERE academy_id = ? AND occurrence_uuid = ?)", Boolean.class, academyId, occurrenceUuid))) {
            return Result.ALREADY_DECIDED;
        }
        List<UUID> candidates = sessions.scheduledOnWebinarAround(academyId, webinarId, eventTime, SLACK);
        if (candidates.size() != 1) {
            Result result = candidates.isEmpty() ? Result.NO_MATCH : Result.AMBIGUOUS;
            log.info("Zoom occurrence on webinar {} left for review: {}", webinarId, result);
            return result;
        }
        // A concurrent worker may have linked it a moment ago; the first decision stands.
        int inserted = jdbc.update("""
                INSERT INTO zoom_occurrence_links (academy_id, occurrence_uuid, webinar_id, class_session_id, link_source, linked_at)
                VALUES (?, ?, ?, ?, 'AUTOMATIC', ?)
                ON CONFLICT (academy_id, occurrence_uuid) DO NOTHING
                """, academyId, occurrenceUuid, webinarId, candidates.get(0), Timestamp.from(DbTime.now()));
        return inserted == 1 ? Result.LINKED : Result.ALREADY_DECIDED;
    }
}
