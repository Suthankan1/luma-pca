package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.ProvisionalAttendanceService.FactWindow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores rebuildable Zoom visit summaries. All official outcomes still come from attendance records. */
@Service
public class AttendanceProjectionStore {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    AttendanceProjectionStore(JdbcTemplate jdbc, @Value("${luma.attendance.projection.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    record ProjectedVisit(int visits, long connectedSeconds) {
    }

    record Job(UUID academyId, FactWindow window, long sourceRevision, long identityRevision) {
    }

    Map<UUID, Map<UUID, ProjectedVisit>> ready(UUID academyId, Set<UUID> ids) {
        if (!enabled || ids.isEmpty()) return Map.of();
        Map<UUID, Map<UUID, ProjectedVisit>> result = new HashMap<>();
        jdbc.query("""
                SELECT j.class_session_id, v.student_id, v.visit_count, v.connected_seconds
                FROM attendance_projection_jobs j
                LEFT JOIN attendance_projection_identity i ON i.academy_id = j.academy_id
                LEFT JOIN attendance_projected_visits v ON v.academy_id = j.academy_id AND v.class_session_id = j.class_session_id
                WHERE j.academy_id = ? AND j.class_session_id = ANY (?) AND j.published_revision = j.source_revision
                  AND j.published_identity_revision = COALESCE(i.revision, 0)
                """, row -> {
            Map<UUID, ProjectedVisit> visits = result.computeIfAbsent(row.getObject(1, UUID.class), id -> new HashMap<>());
            UUID student = row.getObject(2, UUID.class);
            if (student != null) visits.put(student, new ProjectedVisit(row.getInt(3), row.getLong(4)));
        }, academyId, ids.toArray(UUID[]::new));
        return result;
    }

    /** Claim a short lease. Invalidated classes become eligible immediately. Other failures retry after the lease. */
    List<Job> claim(int limit) {
        return jdbc.query("""
                WITH candidates AS (
                  SELECT j.academy_id, j.class_session_id, c.starts_at, c.ends_at,
                         COALESCE(i.revision, 0) AS identity_revision
                  FROM attendance_projection_jobs j JOIN class_sessions c
                    ON c.academy_id = j.academy_id AND c.id = j.class_session_id
                  LEFT JOIN attendance_projection_identity i ON i.academy_id = j.academy_id
                  WHERE (j.next_attempt_at IS NULL OR j.next_attempt_at <= now())
                    AND (j.published_revision IS DISTINCT FROM j.source_revision
                      OR j.published_identity_revision IS DISTINCT FROM COALESCE(i.revision, 0))
                    AND c.status = 'SCHEDULED' AND c.ends_at <= now()
                    AND EXISTS (SELECT 1 FROM zoom_occurrence_links l JOIN zoom_report_fetches f USING (academy_id, occurrence_uuid)
                      WHERE l.academy_id = j.academy_id AND l.class_session_id = j.class_session_id AND f.status = 'FETCHED')
                  ORDER BY c.ends_at DESC, j.class_session_id LIMIT ? FOR UPDATE OF j SKIP LOCKED
                )
                UPDATE attendance_projection_jobs j SET next_attempt_at = now() + interval '5 minutes'
                FROM candidates c WHERE j.academy_id = c.academy_id AND j.class_session_id = c.class_session_id
                RETURNING j.academy_id, j.class_session_id, c.starts_at, c.ends_at, j.source_revision, c.identity_revision
                """, (row, index) -> new Job(row.getObject(1, UUID.class), new FactWindow(row.getObject(2, UUID.class),
                row.getTimestamp(3).toInstant(), row.getTimestamp(4).toInstant()), row.getLong(5), row.getLong(6)), limit);
    }

    @Transactional
    public boolean publish(Job job, Map<UUID, ProjectedVisit> visits) {
        UUID academy = job.academyId();
        UUID session = job.window().sessionId();
        // Erasure takes the student lock before invalidating affected jobs. Use that order when publishing too.
        if (!visits.isEmpty() && jdbc.queryForList("""
                SELECT id FROM students WHERE academy_id = ? AND id = ANY (?) ORDER BY id FOR KEY SHARE SKIP LOCKED
                """, UUID.class, academy, visits.keySet().toArray(UUID[]::new)).size() != visits.size()) return false;
        int updated = jdbc.update("""
                UPDATE attendance_projection_jobs SET published_revision = source_revision, published_identity_revision = ?, next_attempt_at = NULL
                WHERE academy_id = ? AND class_session_id = ? AND source_revision = ?
                  AND ? = COALESCE((SELECT revision FROM attendance_projection_identity WHERE academy_id = ?), 0)
                  AND (published_revision IS DISTINCT FROM source_revision OR published_identity_revision IS DISTINCT FROM ?)
                """, job.identityRevision(), academy, session, job.sourceRevision(), job.identityRevision(), academy, job.identityRevision());
        if (updated == 0) return false;
        jdbc.update("DELETE FROM attendance_projected_visits WHERE academy_id = ? AND class_session_id = ?", academy, session);
        jdbc.batchUpdate("""
                INSERT INTO attendance_projected_visits (academy_id, class_session_id, student_id, visit_count, connected_seconds)
                VALUES (?, ?, ?, ?, ?)
                """, visits.entrySet().stream().map(entry -> new Object[] {academy, session, entry.getKey(),
                entry.getValue().visits(), entry.getValue().connectedSeconds()}).toList());
        return true;
    }

    /** The caller is the authorized erasure transaction and has already invalidated the student's matching inputs. */
    public void eraseProjectedVisits(UUID academyId, UUID studentId) {
        jdbc.update("DELETE FROM attendance_projected_visits WHERE academy_id = ? AND student_id = ?", academyId, studentId);
    }
}
