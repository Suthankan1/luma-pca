package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.domain.AttendanceDurationCalculator;
import com.physicscube.luma.attendance.domain.ConnectionInterval;
import com.physicscube.luma.enrollments.ExpectedStudent;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.SessionView;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentView;
import com.physicscube.luma.zoom.ZoomParticipantService;
import com.physicscube.luma.zoom.ZoomParticipantService.ClassActivity;
import com.physicscube.luma.zoom.ZoomParticipantService.StudentVisit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance facts for one class, recalculated on every read (slices 004A/004B): from Zoom's
 * participant report where it has been fetched after the webinar, otherwise from live events. It reports facts only: time connected (rejoins merged, clipped to the class
 * time), first join and how long after the start, visits, and visits with no leave yet.
 * It never decides present or absent: the academy's threshold is an open question, live
 * events can be late or missing, and someone not seen is not proof of absence. Official
 * reconciliation with Zoom's report is 004B.
 */
@Service
public class ProvisionalAttendanceService {
    private final ClassSessionService sessions;
    private final ZoomParticipantService zoom;
    private final StudentService students;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    ProvisionalAttendanceService(ClassSessionService sessions, ZoomParticipantService zoom, StudentService students,
                                 org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.zoom = zoom;
        this.students = students;
        this.jdbc = jdbc;
    }

    /**
     * @param expected expected at the class (enrolled in one of its batches on its date)
     * @param connectedSeconds time connected within the class time, rejoins merged
     * @param firstJoinAt the earliest join; a visit whose join never arrived counts from the webinar's start
     * @param secondsAfterStart how long after the class start the first join was; 0 when on time or early
     * @param openVisits visits with no leave received: counted up to the webinar's end, or to now while it runs
     * @param recordId the final record, once the class is finalised; {@code null} before
     * @param outcome the outcome in force on the final record (PRESENT, LATE, ABSENT or EXCUSED); {@code null} before
     */
    public record StudentAttendance(UUID studentId, String studentNumber, String fullName, boolean expected, boolean seen,
                                    int visits, int openVisits, long connectedSeconds, Instant firstJoinAt, Long secondsAfterStart,
                                    UUID recordId, String outcome) {
        StudentAttendance withRecord(UUID id, String finalOutcome) {
            return new StudentAttendance(studentId, studentNumber, fullName, expected, seen, visits, openVisits, connectedSeconds,
                    firstJoinAt, secondsAfterStart, id, finalOutcome);
        }
    }

    /**
     * @param status PROVISIONAL (some or all from live events) or RECONCILED (every linked occurrence from Zoom's
     *               participant report). Neither is a present/absent decision.
     * @param webinarEnded every linked occurrence has a webinar.ended event or a fetched report
     * @param reportedOccurrences linked occurrences counted from Zoom's participant report
     * @param unmatchedPeople Zoom participants not matched to any student (see Zoom review)
     * @param finalisedAt when the class was finalised against the academy's rule; {@code null} before
     */
    public record ProvisionalAttendance(UUID sessionId, String status, Instant classStartsAt, Instant classEndsAt,
                                        int linkedOccurrences, int reportedOccurrences, boolean webinarEnded, int unmatchedPeople,
                                        Instant calculatedAt, List<StudentAttendance> students, Instant finalisedAt) {
    }

    @Transactional(readOnly = true)
    public ProvisionalAttendance forClass(CurrentAccount caller, UUID sessionId) {
        SessionView session = sessions.get(caller, sessionId); // access check: batch-scoped staff see their classes only
        List<ExpectedStudent> expected = sessions.expectedStudents(caller, sessionId);
        ClassActivity activity = zoom.activityForClass(caller.academyId(), sessionId);
        Instant now = DbTime.now();

        Map<UUID, List<StudentVisit>> byStudent = activity.visits().stream().collect(Collectors.groupingBy(StudentVisit::studentId));
        Set<UUID> expectedIds = expected.stream().map(ExpectedStudent::studentId).collect(Collectors.toSet());
        List<StudentAttendance> rows = new ArrayList<>();
        for (ExpectedStudent student : expected) {
            rows.add(row(student.studentId(), student.studentNumber(), student.fullName(), true,
                    byStudent.getOrDefault(student.studentId(), List.of()), activity, session, now));
        }
        // Students seen but not expected: only staff who see the whole academy are shown them.
        if (caller.isAcademyManager()) {
            Set<UUID> extra = new HashSet<>(byStudent.keySet());
            extra.removeAll(expectedIds);
            Map<UUID, StudentView> named = students.viewsInAcademy(caller.academyId(), extra);
            for (UUID id : extra) {
                StudentView student = named.get(id);
                if (student != null) {
                    rows.add(row(id, student.studentNumber(), student.fullName(), false, byStudent.get(id), activity, session, now));
                }
            }
        }
        rows.sort(Comparator.comparing(StudentAttendance::expected).reversed().thenComparing(StudentAttendance::studentNumber));
        boolean reconciled = !activity.occurrences().isEmpty() && activity.reportedOccurrences().containsAll(activity.occurrences());
        boolean ended = !activity.occurrences().isEmpty() && activity.occurrences().stream()
                .allMatch(o -> activity.endedAt().containsKey(o) || activity.reportedOccurrences().contains(o));
        // Final records, once the class is finalised (004B); corrections change the outcome in force (004C).
        Map<UUID, Object[]> records = new java.util.HashMap<>();
        Instant[] finalisedAt = new Instant[1];
        jdbc.query("SELECT id, student_id, outcome, finalised_at FROM attendance_records WHERE academy_id = ? AND class_session_id = ?",
                row -> {
                    records.put((UUID) row.getObject("student_id"), new Object[] {row.getObject("id"), row.getString("outcome")});
                    finalisedAt[0] = row.getTimestamp("finalised_at").toInstant();
                }, caller.academyId(), sessionId);
        List<StudentAttendance> withRecords = rows.stream().map(r -> records.containsKey(r.studentId())
                ? r.withRecord((UUID) records.get(r.studentId())[0], (String) records.get(r.studentId())[1]) : r).toList();
        return new ProvisionalAttendance(sessionId, reconciled ? "RECONCILED" : "PROVISIONAL", session.startsAt(), session.endsAt(),
                activity.occurrences().size(), activity.reportedOccurrences().size(), ended, activity.unmatchedPeople(), now, withRecords,
                finalisedAt[0]);
    }

    private static StudentAttendance row(UUID id, String number, String name, boolean expected, List<StudentVisit> visits,
                                         ClassActivity activity, SessionView session, Instant now) {
        List<ConnectionInterval> intervals = new ArrayList<>();
        Instant firstJoin = null;
        int open = 0;
        for (StudentVisit visit : visits) {
            Instant joined = visit.joinedAt() != null ? visit.joinedAt() : activity.startedAt().get(visit.occurrenceUuid());
            Instant left = visit.leftAt();
            if (left == null) {
                open++;
                Instant end = activity.endedAt().get(visit.occurrenceUuid());
                left = end != null ? end : now;
            }
            if (joined == null) {
                continue;
            }
            firstJoin = firstJoin == null || joined.isBefore(firstJoin) ? joined : firstJoin;
            if (left.isAfter(joined)) {
                intervals.add(new ConnectionInterval(joined, left));
            }
        }
        Duration connected = intervals.isEmpty() ? Duration.ZERO
                : AttendanceDurationCalculator.connectedDuration(session.startsAt(), session.endsAt(), intervals);
        Long after = firstJoin == null ? null : Math.max(0, Duration.between(session.startsAt(), firstJoin).toSeconds());
        return new StudentAttendance(id, number, name, expected, !visits.isEmpty(), visits.size(), open, connected.toSeconds(),
                firstJoin, after, null, null);
    }
}
