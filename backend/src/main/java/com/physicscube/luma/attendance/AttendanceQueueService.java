package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.ProvisionalAttendanceService.ProvisionalAttendance;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Classes that have ended but are not final: what each is waiting for, finalising every ready one at once, and the
 * optional automatic finalisation (switched on by staff) once Zoom's report is in. Finalisation itself is
 * {@link AttendanceFinalisationService#finalise}, unchanged: the same rule, checks and audit.
 */
@Service
public class AttendanceQueueService {
    private static final Logger log = LoggerFactory.getLogger(AttendanceQueueService.class);
    /** Classes older than this are not offered; recalculation or correction handles anything older. */
    static final int LOOKBACK_DAYS = 60;
    static final int MAX_CLASSES = 200;

    private final JdbcTemplate jdbc;
    private final ProvisionalAttendanceService attendance;
    private final AttendanceFinalisationService finalisation;
    private final AttendancePolicyService policies;

    AttendanceQueueService(JdbcTemplate jdbc, ProvisionalAttendanceService attendance, AttendanceFinalisationService finalisation,
                           AttendancePolicyService policies) {
        this.jdbc = jdbc;
        this.attendance = attendance;
        this.finalisation = finalisation;
        this.policies = policies;
    }

    /**
     * @param state READY (every linked Zoom run has its report), WAITING_FOR_REPORT, or NO_ZOOM_RUN (nothing linked:
     *              link a run in Zoom review or "Check a webinar")
     */
    public record PendingClass(UUID sessionId, String title, Instant startsAt, Instant endsAt, String timeZone, int linkedRuns,
                               String state) {
    }

    public record Pending(boolean ruleRecorded, boolean autoFinalise, List<PendingClass> classes) {
    }

    public record BulkResult(int finalised, int notReady, List<String> problems) {
    }

    /** Ended, scheduled classes of the last {@value #LOOKBACK_DAYS} days with no final attendance, oldest first. */
    public Pending pending(CurrentAccount caller) {
        caller.requireStaff();
        Instant now = DbTime.now();
        List<PendingClass> result = new ArrayList<>();
        for (Object[] row : candidates(caller.academyId(), now)) {
            UUID id = (UUID) row[0];
            ProvisionalAttendance facts = attendance.forClass(caller, id);
            if ("NO_ATTENDEES".equals(facts.status())) continue;
            String state = facts.linkedOccurrences() == 0 ? "NO_ZOOM_RUN" : "RECONCILED".equals(facts.status()) ? "READY" : "WAITING_FOR_REPORT";
            result.add(new PendingClass(id, (String) row[1], ((Timestamp) row[2]).toInstant(), ((Timestamp) row[3]).toInstant(),
                    (String) row[4], facts.linkedOccurrences(), state));
        }
        var policy = policies.find(caller.academyId());
        return new Pending(policy.isPresent(), policy.map(AttendancePolicyService.PolicyView::autoFinalise).orElse(false), result);
    }

    /** Finalises every ready class; the others are left as they are. Each class is its own transaction. */
    public BulkResult finaliseReady(CurrentAccount caller) {
        caller.requireStaff();
        if (policies.find(caller.academyId()).isEmpty()) {
            throw new ConflictException("The academy has not recorded its attendance rule yet");
        }
        int done = 0;
        int notReady = 0;
        List<String> problems = new ArrayList<>();
        for (PendingClass c : pending(caller).classes()) {
            if (!"READY".equals(c.state())) {
                notReady++;
                continue;
            }
            try {
                finalisation.finalise(caller, c.sessionId());
                done++;
            } catch (ConflictException e) {
                problems.add(c.title() + ": " + e.getMessage());
            }
        }
        return new BulkResult(done, notReady, problems);
    }

    /**
     * For academies where a staff member switched automatic finalisation on: finalises every ready class in that staff
     * member's name, as long as they are still active staff. Returns how many classes were finalised.
     */
    public int autoFinaliseOnce() {
        int done = 0;
        Instant now = DbTime.now();
        for (var row : jdbc.queryForList("""
                SELECT p.academy_id, p.auto_finalise_by FROM attendance_policies p
                JOIN accounts a ON a.id = p.auto_finalise_by AND a.academy_id = p.academy_id AND a.status = 'ACTIVE'
                JOIN account_roles r ON r.account_id = a.id AND r.role = 'STAFF'
                WHERE p.auto_finalise
                """)) {
            UUID academy = (UUID) row.get("academy_id");
            CurrentAccount owner = new CurrentAccount((UUID) row.get("auto_finalise_by"), academy, Set.of(CurrentAccount.STAFF));
            for (Object[] c : candidates(academy, now)) {
                UUID id = (UUID) c[0];
                try {
                    ProvisionalAttendance facts = attendance.forClass(owner, id);
                    if (facts.linkedOccurrences() > 0 && "RECONCILED".equals(facts.status())) {
                        finalisation.finaliseAutomatically(owner, id);
                        done++;
                    }
                } catch (ConflictException e) {
                    // Someone finalised it meanwhile, or it changed: the next run looks again.
                } catch (RuntimeException e) {
                    log.warn("Automatic finalisation skipped a class: {}", e.getClass().getSimpleName());
                }
            }
        }
        return done;
    }

    private List<Object[]> candidates(UUID academy, Instant now) {
        return jdbc.query("""
                SELECT cs.id, cs.title, cs.starts_at, cs.ends_at, cs.time_zone FROM class_sessions cs
                WHERE cs.academy_id = ? AND cs.status = 'SCHEDULED' AND cs.ends_at <= ? AND cs.ends_at > ?
                  AND NOT EXISTS (SELECT 1 FROM attendance_records ar WHERE ar.class_session_id = cs.id)
                  AND NOT EXISTS (SELECT 1 FROM zoom_classes_without_attendees z
                                  WHERE z.academy_id = cs.academy_id AND z.class_session_id = cs.id)
                ORDER BY cs.starts_at LIMIT ?
                """, (r, i) -> new Object[] {r.getObject(1, UUID.class), r.getString(2), r.getTimestamp(3), r.getTimestamp(4), r.getString(5)},
                academy, Timestamp.from(now), Timestamp.from(now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS)), MAX_CLASSES);
    }
}
