package com.physicscube.luma.attendance;

import com.fasterxml.jackson.annotation.JsonInclude;
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
     * @param recordVersion the final record's version, for corrections (004C); {@code null} before
     */
    public record StudentAttendance(UUID studentId, String studentNumber, String fullName, boolean expected, boolean seen,
                                    int visits, int openVisits, long connectedSeconds, Instant firstJoinAt, Long secondsAfterStart,
                                    UUID recordId, String outcome, Long recordVersion) {
        StudentAttendance withRecord(UUID id, String finalOutcome, long version) {
            return new StudentAttendance(studentId, studentNumber, fullName, expected, seen, visits, openVisits, connectedSeconds,
                    firstJoinAt, secondsAfterStart, id, finalOutcome, version);
        }
    }

    /**
     * @param status PROVISIONAL (some or all from live events) or RECONCILED (every linked occurrence from Zoom's
     *               participant report). Neither is a present/absent decision.
     * @param webinarEnded every linked occurrence has a webinar.ended event or a fetched report
     * @param reportedOccurrences linked occurrences counted from Zoom's participant report
     * @param unmatchedPeople Zoom participants not matched to any student (see Zoom review)
     * @param finalisedAt when the class was finalised against the academy's rule; {@code null} before
     * @param onlineHistory sampled online counts for visible matched students, omitted unless requested
     */
    public record ProvisionalAttendance(UUID sessionId, String status, Instant classStartsAt, Instant classEndsAt,
                                        int linkedOccurrences, int reportedOccurrences, boolean webinarEnded, int unmatchedPeople,
                                        Instant calculatedAt, List<StudentAttendance> students, Instant finalisedAt,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) List<OnlineSample> onlineHistory) {
    }

    public record OnlineSample(Instant at, int online) {
    }

    private record FinalRecords(Map<UUID, Object[]> records, Instant finalisedAt) {
    }

    /** The class's final records by student (id, outcome, version), once it is finalised. */
    private FinalRecords finalRecords(UUID academyId, UUID sessionId) {
        Map<UUID, Object[]> records = new java.util.HashMap<>();
        Instant[] finalisedAt = new Instant[1];
        jdbc.query("SELECT id, student_id, outcome, finalised_at, version FROM attendance_records WHERE academy_id = ? AND class_session_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    records.put((UUID) row.getObject("student_id"), new Object[] {row.getObject("id"), row.getString("outcome"), row.getLong("version")});
                    finalisedAt[0] = row.getTimestamp("finalised_at").toInstant();
                }, academyId, sessionId);
        return new FinalRecords(records, finalisedAt[0]);
    }

    @Transactional(readOnly = true)
    public ProvisionalAttendance forClass(CurrentAccount caller, UUID sessionId) {
        return forClass(caller, sessionId, false);
    }

    @Transactional(readOnly = true)
    public ProvisionalAttendance forClass(CurrentAccount caller, UUID sessionId, boolean includeOnlineHistory) {
        // Zoom activity and final records are read at the same time as the class (they need only the academy and class
        // IDs); nothing they return is used unless the access check below passes.
        UUID academyId = caller.academyId();
        var activityTask = com.physicscube.luma.shared.concurrent.Parallel.start(() -> zoom.activityForClass(academyId, sessionId));
        var recordsTask = com.physicscube.luma.shared.concurrent.Parallel.start(() -> finalRecords(academyId, sessionId));
        SessionView session = sessions.get(caller, sessionId); // access check: batch-scoped staff see their classes only
        List<ExpectedStudent> expected = sessions.expectedStudents(caller, session);
        ClassActivity activity = com.physicscube.luma.shared.concurrent.Parallel.join(activityTask);
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
        FinalRecords finals = com.physicscube.luma.shared.concurrent.Parallel.join(recordsTask);
        Map<UUID, Object[]> records = finals.records();
        Instant[] finalisedAt = {finals.finalisedAt()};
        List<StudentAttendance> withRecords = rows.stream().map(r -> records.containsKey(r.studentId())
                ? r.withRecord((UUID) records.get(r.studentId())[0], (String) records.get(r.studentId())[1], (Long) records.get(r.studentId())[2]) : r).toList();
        List<OnlineSample> onlineHistory = includeOnlineHistory ? OnlineHistory.sample(activity,
                rows.stream().map(StudentAttendance::studentId).collect(Collectors.toSet()), session.startsAt(), session.endsAt(), now) : null;
        return new ProvisionalAttendance(sessionId, reconciled ? "RECONCILED" : "PROVISIONAL", session.startsAt(), session.endsAt(),
                activity.occurrences().size(), activity.reportedOccurrences().size(), ended, activity.unmatchedPeople(), now, withRecords,
                finalisedAt[0], onlineHistory);
    }

    /**
     * One connection of a student to a class's webinar, as seen in Zoom: a leave followed by a join is a rejoin.
     *
     * @param joinedAt when the connection started (the webinar's start when only the leave arrived)
     * @param leftAt when it ended; for an open connection the webinar's end, or now while it runs
     * @param open no leave has arrived (still connected, or the leave was missed)
     * @param secondsInClass this connection's time inside the class time (connections can overlap; the class total
     *                       merges them and never counts any moment twice)
     */
    public record Visit(Instant joinedAt, Instant leftAt, boolean open, long secondsInClass) {
    }

    /** One student's facts for one class, with every connection. {@code connectedSeconds} never exceeds the class length. */
    public record StudentFacts(boolean seen, int visits, int openVisits, long connectedSeconds, Instant firstJoinAt,
                               Long secondsAfterStart, List<Visit> timeline) {
    }

    /**
     * @param state FINAL (finalised against the academy's rule), REPORT (Zoom's participant report, not yet finalised),
     *              ENDED (live events only, webinar over), LIVE (running now) or NO_WEBINAR (no Zoom webinar linked)
     * @param finals per student: the final record's outcome and its officially recorded connected seconds
     */
    public record ClassFacts(String state, Map<UUID, StudentFacts> students, Map<UUID, FinalOutcome> finals) {
    }

    public record FinalOutcome(String outcome, long connectedSeconds) {
    }

    /** Count expected students with a matched visit, using the same evidence as the detailed dashboard. */
    int countJoined(UUID academyId, UUID sessionId, Set<UUID> expected) {
        if (expected.isEmpty()) {
            return 0;
        }
        return (int) zoom.activityForClass(academyId, sessionId).visits().stream()
                .map(StudentVisit::studentId).filter(expected::contains).distinct().count();
    }

    /**
     * For other services in this module: attendance facts of every matched student of one class. No access check: the
     * caller has checked the class and filters the students it may show.
     */
    ClassFacts factsFor(UUID academyId, UUID sessionId, Instant startsAt, Instant endsAt) {
        ClassActivity activity = zoom.activityForClass(academyId, sessionId);
        Map<UUID, FinalOutcome> finals = new java.util.HashMap<>();
        jdbc.query("SELECT student_id, outcome, connected_seconds FROM attendance_records WHERE academy_id = ? AND class_session_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> finals.put((UUID) r.getObject(1),
                        new FinalOutcome(r.getString(2), r.getLong(3))), academyId, sessionId);
        Instant now = DbTime.now();
        Map<UUID, List<StudentVisit>> byStudent = activity.visits().stream().collect(Collectors.groupingBy(StudentVisit::studentId));
        Map<UUID, StudentFacts> facts = new java.util.HashMap<>();
        byStudent.forEach((id, visits) -> facts.put(id, facts(visits, activity, startsAt, endsAt, now)));
        boolean reconciled = !activity.occurrences().isEmpty() && activity.reportedOccurrences().containsAll(activity.occurrences());
        boolean ended = !activity.occurrences().isEmpty() && activity.occurrences().stream()
                .allMatch(o -> activity.endedAt().containsKey(o) || activity.reportedOccurrences().contains(o));
        String state = !finals.isEmpty() ? "FINAL" : activity.occurrences().isEmpty() ? "NO_WEBINAR" : reconciled ? "REPORT"
                : ended || now.isAfter(endsAt.plus(Duration.ofHours(2))) ? "ENDED" : "LIVE";
        return new ClassFacts(state, facts, finals);
    }

    private static StudentFacts facts(List<StudentVisit> visits, ClassActivity activity, Instant startsAt, Instant endsAt, Instant now) {
        List<Visit> timeline = new ArrayList<>();
        List<ConnectionInterval> intervals = new ArrayList<>();
        Instant firstJoin = null;
        int open = 0;
        for (StudentVisit visit : visits) {
            Instant joined = visit.joinedAt() != null ? visit.joinedAt() : activity.startedAt().get(visit.occurrenceUuid());
            Instant left = visit.leftAt();
            boolean isOpen = left == null;
            if (isOpen) {
                open++;
                Instant end = activity.endedAt().get(visit.occurrenceUuid());
                left = end != null ? end : now;
            }
            if (joined == null) {
                continue;
            }
            firstJoin = firstJoin == null || joined.isBefore(firstJoin) ? joined : firstJoin;
            long inClass = 0;
            if (left.isAfter(joined)) {
                intervals.add(new ConnectionInterval(joined, left));
                inClass = AttendanceDurationCalculator.connectedDuration(startsAt, endsAt, List.of(new ConnectionInterval(joined, left))).toSeconds();
            }
            timeline.add(new Visit(joined, left, isOpen, inClass));
        }
        timeline.sort(Comparator.comparing(Visit::joinedAt));
        long connected = intervals.isEmpty() ? 0 : AttendanceDurationCalculator.connectedDuration(startsAt, endsAt, intervals).toSeconds();
        Long after = firstJoin == null ? null : Math.max(0, Duration.between(startsAt, firstJoin).toSeconds());
        return new StudentFacts(true, visits.size(), open, connected, firstJoin, after, List.copyOf(timeline));
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
                firstJoin, after, null, null, null);
    }
}
