package com.physicscube.luma.zoom;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.sessions.ClassSessionChanged;
import com.physicscube.luma.sessions.ClassSessionChanged.ZoomSlot;
import com.physicscube.luma.sessions.ClassSessionService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps automatic Zoom links true to the timetable when a class is created or changed.
 *
 * <p>The occurrences that can be affected are those linked automatically to the class, and
 * every occurrence on the class's webinar whose first event lies within the matcher's slack
 * of the class's time, before or after the change. For each of them:
 * <ul>
 *   <li>an automatic link is kept only while the matcher would still make it now (exactly one
 *       scheduled class fits, and it is the linked one); otherwise it is removed and the
 *       occurrence is matched again, moving to the one class that now fits or back to review;</li>
 *   <li>an undecided occurrence is matched again;</li>
 *   <li>a manual decision is never changed.</li>
 * </ul>
 * Runs in the transaction that changed the class.
 */
@Component
class ClassLinkReviser {
    private static final Logger log = LoggerFactory.getLogger(ClassLinkReviser.class);

    private final JdbcTemplate jdbc;
    private final OccurrenceLinker linker;
    private final ClassSessionService sessions;
    private final AuditLog audit;

    ClassLinkReviser(JdbcTemplate jdbc, OccurrenceLinker linker, ClassSessionService sessions, AuditLog audit) {
        this.jdbc = jdbc;
        this.linker = linker;
        this.sessions = sessions;
        this.audit = audit;
    }

    /** @param unlinked automatic links removed and left for review; @param moved relinked to another class; @param linked undecided ones now linked */
    record Revision(int unlinked, int moved, int linked) {
    }

    private record Affected(String occurrenceUuid, String webinarId, Instant firstEvent, UUID linkedSession, String source) {
    }

    @EventListener
    void on(ClassSessionChanged changed) {
        revise(changed);
    }

    Revision revise(ClassSessionChanged changed) {
        UUID academyId = changed.academyId();
        Map<String, Affected> affected = new LinkedHashMap<>();
        jdbc.query("""
                SELECT l.occurrence_uuid, l.webinar_id, l.class_session_id, l.link_source,
                       (SELECT min(e.event_ts) FROM zoom_occurrence_events e
                        WHERE e.academy_id = l.academy_id AND e.occurrence_uuid = l.occurrence_uuid) AS first_ts
                FROM zoom_occurrence_links l
                WHERE l.academy_id = ? AND l.class_session_id = ? AND l.link_source = 'AUTOMATIC'
                ORDER BY l.occurrence_uuid
                FOR UPDATE OF l
                """, row -> {
            affected.put(row.getString("occurrence_uuid"), new Affected(row.getString("occurrence_uuid"), row.getString("webinar_id"),
                    Instant.ofEpochMilli(row.getLong("first_ts")), changed.sessionId(), "AUTOMATIC"));
        }, academyId, changed.sessionId());
        for (ZoomSlot slot : new ZoomSlot[] {changed.before(), changed.after()}) {
            if (slot == null) {
                continue;
            }
            jdbc.query("""
                    SELECT t.occurrence_uuid, t.first_ts, l.class_session_id, l.link_source
                    FROM (SELECT e.occurrence_uuid, min(e.event_ts) AS first_ts FROM zoom_occurrence_events e
                          WHERE e.academy_id = ? AND e.webinar_id = ?
                          GROUP BY e.occurrence_uuid HAVING min(e.event_ts) BETWEEN ? AND ?) t
                    LEFT JOIN zoom_occurrence_links l ON l.academy_id = ? AND l.occurrence_uuid = t.occurrence_uuid
                    ORDER BY t.first_ts, t.occurrence_uuid
                    """, row -> {
                affected.putIfAbsent(row.getString("occurrence_uuid"), new Affected(row.getString("occurrence_uuid"), slot.webinarId(),
                        Instant.ofEpochMilli(row.getLong("first_ts")), (UUID) row.getObject("class_session_id"),
                        row.getString("link_source")));
            }, academyId, slot.webinarId(), slot.startsAt().minus(OccurrenceLinker.SLACK).toEpochMilli(),
                    slot.endsAt().plus(OccurrenceLinker.SLACK).toEpochMilli(), academyId);
        }

        int unlinked = 0;
        int moved = 0;
        int linked = 0;
        for (Affected occurrence : affected.values()) {
            if ("MANUAL".equals(occurrence.source())) {
                continue;
            }
            if ("AUTOMATIC".equals(occurrence.source())) {
                List<UUID> fits = sessions.scheduledOnWebinarAround(academyId, occurrence.webinarId(), occurrence.firstEvent(),
                        OccurrenceLinker.SLACK);
                if (fits.size() == 1 && fits.get(0).equals(occurrence.linkedSession())) {
                    continue;
                }
                jdbc.update("DELETE FROM zoom_occurrence_links WHERE academy_id = ? AND occurrence_uuid = ? AND link_source = 'AUTOMATIC'",
                        academyId, occurrence.occurrenceUuid());
                if (linker.tryLink(academyId, occurrence.occurrenceUuid(), occurrence.webinarId(), occurrence.firstEvent())
                        == OccurrenceLinker.Result.LINKED) {
                    moved++;
                } else {
                    unlinked++;
                }
            } else if (linker.tryLink(academyId, occurrence.occurrenceUuid(), occurrence.webinarId(), occurrence.firstEvent())
                    == OccurrenceLinker.Result.LINKED) {
                linked++;
            }
        }
        Revision revision = new Revision(unlinked, moved, linked);
        if (unlinked + moved + linked > 0) {
            audit.record(academyId, changed.actorAccountId(), "ZOOM_LINKS_REVISED", "CLASS_SESSION", changed.sessionId(), Map.of(
                    "unlinked", String.valueOf(unlinked), "moved", String.valueOf(moved), "linked", String.valueOf(linked)));
            log.info("Zoom links revised after class {} changed: {} back to review, {} moved, {} linked",
                    changed.sessionId(), unlinked, moved, linked);
        }
        return revision;
    }
}
