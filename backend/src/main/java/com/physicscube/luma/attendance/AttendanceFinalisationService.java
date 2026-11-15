package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.AttendancePolicyService.PolicyView;
import com.physicscube.luma.attendance.ProvisionalAttendanceService.ProvisionalAttendance;
import com.physicscube.luma.attendance.ProvisionalAttendanceService.StudentAttendance;
import com.physicscube.luma.attendance.domain.AttendanceRule;
import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.SessionView;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a class's attendance facts into final records (slice 004B), only when all of this holds:
 * the academy has recorded its rule; the class is scheduled and over; and every linked Zoom
 * occurrence is counted from Zoom's participant report (RECONCILED). Live events alone never
 * finalise anything. Owners and administrators only; once per class (changes are corrections, 004C).
 */
@Service
public class AttendanceFinalisationService {
    private final ProvisionalAttendanceService attendance;
    private final AttendancePolicyService policies;
    private final ClassSessionService sessions;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    AttendanceFinalisationService(ProvisionalAttendanceService attendance, AttendancePolicyService policies, ClassSessionService sessions,
                                  JdbcTemplate jdbc, AuditLog audit) {
        this.attendance = attendance;
        this.policies = policies;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record FinalisationResult(UUID sessionId, Instant finalisedAt, int present, int late, int absent) {
    }

    /** @param changed records whose outcome changed; @param kept corrected records left as they were; @param added new expected students */
    public record RecalculationResult(UUID sessionId, int changed, int kept, int added) {
    }

    /**
     * Recalculates a finalised class from Zoom's current report (fetched again, or a run linked
     * later), with the rule each record was finalised with. Records changed by a correction keep
     * their outcome; their facts are refreshed. Students expected now but without a record get one.
     * Owners and administrators; audited with the reason.
     */
    @Transactional
    public RecalculationResult recalculate(CurrentAccount caller, UUID sessionId, String reason) {
        caller.requireAcademyManager();
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 300) {
            throw new com.physicscube.luma.shared.api.InvalidRequestException("A reason of 3 to 300 characters is required");
        }
        SessionView session = sessions.get(caller, sessionId);
        jdbc.queryForList("SELECT id FROM class_sessions WHERE id = ? AND academy_id = ? FOR UPDATE", sessionId, caller.academyId());
        List<java.util.Map<String, Object>> records = jdbc.queryForList("""
                SELECT r.id, r.student_id, r.computed_outcome, r.outcome, r.rule_min_connected_percent, r.rule_late_after_minutes,
                       EXISTS (SELECT 1 FROM attendance_corrections c WHERE c.record_id = r.id) AS corrected
                FROM attendance_records r WHERE r.academy_id = ? AND r.class_session_id = ?
                """, caller.academyId(), sessionId);
        if (records.isEmpty()) {
            throw new ConflictException("This class is not final yet; finalise it instead");
        }
        ProvisionalAttendance facts = attendance.forClass(caller, sessionId);
        if (!"RECONCILED".equals(facts.status())) {
            throw new ConflictException("Zoom's participant report is needed for every linked occurrence before recalculating");
        }
        Duration length = Duration.between(session.startsAt(), session.endsAt());
        java.util.Map<UUID, StudentAttendance> byStudent = new java.util.HashMap<>();
        facts.students().stream().filter(StudentAttendance::expected).forEach(s -> byStudent.put(s.studentId(), s));
        int changed = 0;
        int kept = 0;
        java.util.Set<UUID> recorded = new java.util.HashSet<>();
        for (java.util.Map<String, Object> r : records) {
            UUID studentId = (UUID) r.get("student_id");
            recorded.add(studentId);
            StudentAttendance s = byStudent.get(studentId);
            AttendanceRule rule = new AttendanceRule(((Number) r.get("rule_min_connected_percent")).intValue(),
                    ((Number) r.get("rule_late_after_minutes")).intValue());
            long connected = s == null ? 0 : s.connectedSeconds();
            Long after = s == null || !s.seen() ? null : s.secondsAfterStart();
            String computed = rule.decide(length, connected, after).name();
            boolean corrected = Boolean.TRUE.equals(r.get("corrected"));
            String outcome = corrected ? (String) r.get("outcome") : computed;
            if (corrected && !computed.equals(r.get("computed_outcome"))) {
                kept++;
            }
            if (!corrected && !computed.equals(r.get("outcome"))) {
                changed++;
            }
            jdbc.update("""
                    UPDATE attendance_records SET computed_outcome = ?, outcome = ?, connected_seconds = ?, first_join_at = ?,
                      seconds_after_start = ?, version = version + 1 WHERE id = ?
                    """, computed, outcome, (int) connected, s == null || s.firstJoinAt() == null ? null : Timestamp.from(s.firstJoinAt()),
                    after == null ? null : after.intValue(), r.get("id"));
        }
        PolicyView policy = policies.find(caller.academyId()).orElse(null);
        int added = 0;
        Instant now = DbTime.now();
        for (StudentAttendance s : byStudent.values()) {
            if (recorded.contains(s.studentId()) || policy == null) {
                continue;
            }
            String computed = policy.rule().decide(length, s.connectedSeconds(), s.seen() ? s.secondsAfterStart() : null).name();
            jdbc.update("""
                    INSERT INTO attendance_records (id, academy_id, class_session_id, student_id, computed_outcome, outcome, connected_seconds,
                      first_join_at, seconds_after_start, rule_min_connected_percent, rule_late_after_minutes, finalised_by, finalised_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), caller.academyId(), sessionId, s.studentId(), computed, computed, (int) s.connectedSeconds(),
                    s.firstJoinAt() == null ? null : Timestamp.from(s.firstJoinAt()), s.secondsAfterStart() == null ? null : s.secondsAfterStart().intValue(),
                    policy.minConnectedPercent(), policy.lateAfterMinutes(), caller.accountId(), Timestamp.from(now));
            added++;
        }
        RecalculationResult result = new RecalculationResult(sessionId, changed, kept, added);
        audit.record(caller.academyId(), caller.accountId(), "ATTENDANCE_RECALCULATED", "CLASS_SESSION", sessionId, java.util.Map.of(
                "changed", String.valueOf(changed), "kept", String.valueOf(kept), "added", String.valueOf(added), "reason", why));
        return result;
    }

    @Transactional
    public FinalisationResult finalise(CurrentAccount caller, UUID sessionId) {
        caller.requireAcademyManager();
        SessionView session = sessions.get(caller, sessionId);
        // One finalisation per class, even if two administrators press at once.
        jdbc.queryForList("SELECT id FROM class_sessions WHERE id = ? AND academy_id = ? FOR UPDATE", sessionId, caller.academyId());
        Integer done = jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE class_session_id = ?", Integer.class, sessionId);
        if (done != null && done > 0) {
            throw new ConflictException("This class's attendance is already final; recalculate it from Zoom's report, or correct a student's record");
        }
        PolicyView policy = policies.find(caller.academyId())
                .orElseThrow(() -> new ConflictException("The academy has not recorded its attendance rule yet"));
        if (!"SCHEDULED".equals(session.status())) {
            throw new ConflictException("A cancelled class has no attendance to finalise");
        }
        Instant now = DbTime.now();
        if (now.isBefore(session.endsAt())) {
            throw new ConflictException("The class has not finished yet");
        }
        ProvisionalAttendance facts = attendance.forClass(caller, sessionId);
        if (!"RECONCILED".equals(facts.status())) {
            throw new ConflictException(facts.linkedOccurrences() == 0
                    ? "No Zoom occurrence is linked to this class"
                    : "Zoom's participant report has not been fetched for every linked occurrence; live events alone are never final");
        }
        AttendanceRule rule = policy.rule();
        Duration length = Duration.between(session.startsAt(), session.endsAt());
        Map<AttendanceRule.Outcome, Integer> counts = new EnumMap<>(AttendanceRule.Outcome.class);
        List<Object[]> rows = new ArrayList<>();
        for (StudentAttendance s : facts.students()) {
            if (!s.expected()) {
                continue; // only students expected at the class get a record
            }
            AttendanceRule.Outcome outcome = rule.decide(length, s.connectedSeconds(), s.seen() ? s.secondsAfterStart() : null);
            counts.merge(outcome, 1, Integer::sum);
            rows.add(new Object[] {UUID.randomUUID(), caller.academyId(), sessionId, s.studentId(), outcome.name(), outcome.name(),
                    (int) s.connectedSeconds(), s.firstJoinAt() == null ? null : Timestamp.from(s.firstJoinAt()),
                    s.secondsAfterStart() == null ? null : s.secondsAfterStart().intValue(), policy.minConnectedPercent(),
                    policy.lateAfterMinutes(), caller.accountId(), Timestamp.from(now)});
        }
        jdbc.batchUpdate("""
                INSERT INTO attendance_records (id, academy_id, class_session_id, student_id, computed_outcome, outcome, connected_seconds,
                  first_join_at, seconds_after_start, rule_min_connected_percent, rule_late_after_minutes, finalised_by, finalised_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        FinalisationResult result = new FinalisationResult(sessionId, now, counts.getOrDefault(AttendanceRule.Outcome.PRESENT, 0),
                counts.getOrDefault(AttendanceRule.Outcome.LATE, 0), counts.getOrDefault(AttendanceRule.Outcome.ABSENT, 0));
        audit.record(caller.academyId(), caller.accountId(), "ATTENDANCE_FINALISED", "CLASS_SESSION", sessionId, Map.of(
                "present", String.valueOf(result.present()), "late", String.valueOf(result.late()), "absent", String.valueOf(result.absent()),
                "rule", policy.minConnectedPercent() + "% / " + policy.lateAfterMinutes() + " min"));
        return result;
    }
}
