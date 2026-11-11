package com.physicscube.luma.zoom;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner/administrator review of Zoom occurrences and their class-session links. */
@Service
public class ZoomOccurrenceService {
    private static final int AUTO_LINK_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final OccurrenceLinker linker;
    private final ClassSessionService sessions;
    private final AuditLog audit;

    ZoomOccurrenceService(JdbcTemplate jdbc, OccurrenceLinker linker, ClassSessionService sessions, AuditLog audit) {
        this.jdbc = jdbc;
        this.linker = linker;
        this.sessions = sessions;
        this.audit = audit;
    }

    public record AutoLinkResult(int linked, int stillUndecided) {
    }

    /**
     * Newest first. {@code decided}: {@code false} for occurrences waiting for review,
     * {@code true} for decided ones, {@code null} for both.
     */
    @Transactional(readOnly = true)
    public List<OccurrenceView> list(CurrentAccount caller, Boolean decided, UUID sessionId, int limit) {
        caller.requireAcademyManager();
        if (limit < 1 || limit > 200) {
            throw new InvalidRequestException("limit must be between 1 and 200");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT e.occurrence_uuid, min(e.webinar_id) AS webinar_id, min(e.event_ts) AS first_ts, max(e.event_ts) AS last_ts,
                       count(*) AS events, l.class_session_id, l.link_source, l.linked_at
                FROM zoom_occurrence_events e
                LEFT JOIN zoom_occurrence_links l ON l.academy_id = e.academy_id AND l.occurrence_uuid = e.occurrence_uuid
                WHERE e.academy_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(caller.academyId()));
        if (decided != null) {
            sql.append(decided ? " AND l.occurrence_uuid IS NOT NULL" : " AND l.occurrence_uuid IS NULL");
        }
        if (sessionId != null) {
            sql.append(" AND l.class_session_id = ?");
            args.add(sessionId);
        }
        sql.append(" GROUP BY e.occurrence_uuid, l.class_session_id, l.link_source, l.linked_at"
                + " ORDER BY min(e.event_ts) DESC, e.occurrence_uuid LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (row, i) -> view(row), args.toArray());
    }

    /**
     * Records an administrator's decision for an occurrence, replacing any earlier
     * one: link it to a class session, or, with {@code sessionId == null}, mark it
     * "not a class". The matcher never changes a manual decision afterwards.
     */
    @Transactional
    public OccurrenceView decide(CurrentAccount caller, String occurrenceUuid, UUID sessionId, String reason) {
        caller.requireAcademyManager();
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 300) {
            throw new InvalidRequestException("A reason of 3 to 300 characters is required");
        }
        List<String> webinar = jdbc.queryForList("SELECT webinar_id FROM zoom_occurrence_events WHERE academy_id = ? "
                + "AND occurrence_uuid = ? LIMIT 1", String.class, caller.academyId(), occurrenceUuid);
        if (webinar.isEmpty()) {
            throw new ResourceNotFoundException("Occurrence");
        }
        if (sessionId != null && "CANCELLED".equals(sessions.requireStatusInAcademy(caller.academyId(), sessionId))) {
            throw new ConflictException("An occurrence cannot be linked to a cancelled class");
        }
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT class_session_id, link_source FROM zoom_occurrence_links "
                + "WHERE academy_id = ? AND occurrence_uuid = ? FOR UPDATE", caller.academyId(), occurrenceUuid);
        jdbc.update("""
                INSERT INTO zoom_occurrence_links (academy_id, occurrence_uuid, webinar_id, class_session_id, link_source, linked_by, linked_at)
                VALUES (?, ?, ?, ?, 'MANUAL', ?, ?)
                ON CONFLICT (academy_id, occurrence_uuid) DO UPDATE SET class_session_id = EXCLUDED.class_session_id,
                  link_source = 'MANUAL', linked_by = EXCLUDED.linked_by, linked_at = EXCLUDED.linked_at
                """, caller.academyId(), occurrenceUuid, webinar.get(0), sessionId, caller.accountId(), Timestamp.from(DbTime.now()));
        Object before = previous.isEmpty() ? null : previous.get(0).get("class_session_id");
        audit.record(caller.academyId(), caller.accountId(), sessionId == null ? "ZOOM_OCCURRENCE_MARKED_NOT_CLASS"
                : "ZOOM_OCCURRENCE_LINKED", "ZOOM_OCCURRENCE", sessionId, Map.of(
                "occurrenceUuid", occurrenceUuid,
                "previousSessionId", before == null ? "none" : before.toString(),
                "previousSource", previous.isEmpty() ? "none" : String.valueOf(previous.get(0).get("link_source")),
                "reason", why));
        return list(caller, true, null, 200).stream().filter(v -> v.occurrenceUuid().equals(occurrenceUuid)).findFirst()
                .orElseThrow();
    }

    /**
     * Runs the automatic matcher again for undecided occurrences, using each one's first
     * event time; useful after adding a webinar ID to a class. Decided ones are untouched.
     */
    @Transactional
    public AutoLinkResult autoLinkUndecided(CurrentAccount caller) {
        caller.requireAcademyManager();
        List<Map<String, Object>> undecided = jdbc.queryForList("""
                SELECT e.occurrence_uuid, min(e.webinar_id) AS webinar_id, min(e.event_ts) AS first_ts
                FROM zoom_occurrence_events e
                WHERE e.academy_id = ? AND NOT EXISTS (SELECT 1 FROM zoom_occurrence_links l
                  WHERE l.academy_id = e.academy_id AND l.occurrence_uuid = e.occurrence_uuid)
                GROUP BY e.occurrence_uuid ORDER BY min(e.event_ts) LIMIT ?
                """, caller.academyId(), AUTO_LINK_BATCH);
        int linked = 0;
        for (Map<String, Object> occurrence : undecided) {
            if (linker.tryLink(caller.academyId(), (String) occurrence.get("occurrence_uuid"), (String) occurrence.get("webinar_id"),
                    Instant.ofEpochMilli(((Number) occurrence.get("first_ts")).longValue())) == OccurrenceLinker.Result.LINKED) {
                linked++;
            }
        }
        return new AutoLinkResult(linked, undecided.size() - linked);
    }

    private static OccurrenceView view(ResultSet row) throws SQLException {
        Timestamp linkedAt = row.getTimestamp("linked_at");
        return new OccurrenceView(row.getString("occurrence_uuid"), row.getString("webinar_id"),
                Instant.ofEpochMilli(row.getLong("first_ts")), Instant.ofEpochMilli(row.getLong("last_ts")), row.getInt("events"),
                (UUID) row.getObject("class_session_id"), row.getString("link_source"),
                linkedAt == null ? null : linkedAt.toInstant());
    }
}
