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

    @Transactional
    public FinalisationResult finalise(CurrentAccount caller, UUID sessionId) {
        caller.requireAcademyManager();
        SessionView session = sessions.get(caller, sessionId);
        // One finalisation per class, even if two administrators press at once.
        jdbc.queryForList("SELECT id FROM class_sessions WHERE id = ? AND academy_id = ? FOR UPDATE", sessionId, caller.academyId());
        Integer done = jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE class_session_id = ?", Integer.class, sessionId);
        if (done != null && done > 0) {
            throw new ConflictException("This class's attendance is already final; change a student's record with a correction");
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
