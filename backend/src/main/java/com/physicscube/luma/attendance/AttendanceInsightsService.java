package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.ProvisionalAttendanceService.ClassFacts;
import com.physicscube.luma.attendance.ProvisionalAttendanceService.FinalOutcome;
import com.physicscube.luma.attendance.ProvisionalAttendanceService.StudentFacts;
import com.physicscube.luma.attendance.ProvisionalAttendanceService.Visit;
import com.physicscube.luma.enrollments.EnrollmentService;
import com.physicscube.luma.enrollments.EnrollmentService.RosterRequest;
import com.physicscube.luma.enrollments.EnrollmentView;
import com.physicscube.luma.enrollments.ExpectedStudent;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.ClassSessionService.CompletedSession;
import com.physicscube.luma.sessions.SessionView;
import com.physicscube.luma.sessions.StudentClassView;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.concurrent.Parallel;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentView;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Attendance dashboards: who attended which classes and for how long, for a course, a batch or one student, from the
 * same facts as the class attendance page (Zoom's report when fetched, otherwise live events; rejoins merged, never
 * longer than the class; the final record once finalised). Only classes that have started are counted. Percentages are
 * time connected divided by class time; outcomes are the academy rule's (or a correction's) once finalised.
 */
@Service
public class AttendanceInsightsService {
    static final ZoneId ACADEMY_ZONE = ZoneId.of(ClassSessionService.DEFAULT_TIME_ZONE);
    /** At most this many classes per dashboard (the most recent); more is reported as truncated. */
    static final int MAX_CLASSES = 80;
    /** Classes computed at the same time: each takes database connections. */
    private static final int CONCURRENCY = 6;
    private static final int RECENT_COMPLETED_CLASSES = 5;

    private final ClassSessionService sessions;
    private final ProvisionalAttendanceService facts;
    private final EnrollmentService enrollments;
    private final StudentService students;
    private final AttendancePolicyService policies;
    private final JdbcTemplate jdbc;

    AttendanceInsightsService(ClassSessionService sessions, ProvisionalAttendanceService facts, EnrollmentService enrollments,
                              StudentService students, AttendancePolicyService policies, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.facts = facts;
        this.enrollments = enrollments;
        this.students = students;
        this.policies = policies;
        this.jdbc = jdbc;
    }

    /** A date range in the academy's time zone, both days included. */
    public record Range(LocalDate from, LocalDate to) {
        Instant start() {
            return from.atStartOfDay(ACADEMY_ZONE).toInstant();
        }

        Instant end() {
            return to.plusDays(1).atStartOfDay(ACADEMY_ZONE).toInstant();
        }
    }

    /** Defaults to the last 30 days; at most 92 days. */
    public static Range range(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ACADEMY_ZONE);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || start.plusDays(91).isBefore(end)) {
            throw new InvalidRequestException("from must not be after to, and the range is at most 92 days");
        }
        return new Range(start, end);
    }

    // ---- Dashboard (staff) ----

    /**
     * @param state       see {@link ClassFacts#state()}
     * @param expected    students expected (in the selection)
     * @param joined      of those, students seen in the webinar
     * @param averagePercent mean of the expected students' connected share, 0-100
     */
    public record ClassSummary(UUID id, String title, Instant startsAt, Instant endsAt, String timeZone, List<String> batchCodes,
                               long classSeconds, String state, int expected, int joined, int averagePercent) {
    }

    /**
     * One student over the selected classes. {@code cells} follows the order of the dashboard's classes; {@code null}
     * where the student was not expected.
     */
    public record StudentSummary(UUID studentId, String studentNumber, String fullName, List<String> batchCodes, int classes,
                                 int joined, long connectedSeconds, long classSeconds, int percent, int present, int late,
                                 int absent, int excused, int rejoins, List<Cell> cells) {
    }

    /** @param outcome final outcome once finalised, else {@code null} */
    public record Cell(long connectedSeconds, int percent, int visits, String outcome, boolean connectedNow) {
    }

    /** @param minPercent the academy rule's threshold, {@code null} when no rule is recorded */
    public record Totals(int classes, int students, int averagePercent, long classSeconds, int belowThreshold, Integer minPercent,
                         int connectedNow) {
    }

    public record Dashboard(LocalDate from, LocalDate to, UUID courseId, UUID batchId, boolean truncated, Totals totals,
                            List<ClassSummary> classes, List<StudentSummary> students) {
    }

    public record ClassTotals(UUID id, int expected, int joined) {
    }

    public record CompletedClassAttendance(UUID id, String title, Instant endsAt, String timeZone, int expected, int joined) {
    }

    public Optional<CompletedClassAttendance> latestCompleted(CurrentAccount caller) {
        return recentCompleted(caller, 1).stream().findFirst();
    }

    public List<CompletedClassAttendance> recentCompleted(CurrentAccount caller) {
        return recentCompleted(caller, RECENT_COMPLETED_CLASSES);
    }

    private List<CompletedClassAttendance> recentCompleted(CurrentAccount caller, int limit) {
        UUID academyId = caller.academyId();
        List<CompletedSession> completed = sessions.recentCompleted(caller, DbTime.now(), limit);
        List<RosterRequest> requests = completed.stream()
                .map(session -> new RosterRequest(session.id(), session.localDate(), session.batchIds())).toList();
        Map<UUID, Set<UUID>> expectedByClass = enrollments.expectedStudentIds(academyId, requests);

        List<CompletableFuture<CompletedClassAttendance>> tasks = completed.stream().map(session -> Parallel.start(() -> {
            Set<UUID> expected = expectedByClass.getOrDefault(session.id(), Set.of());
            int joined = facts.countJoined(academyId, session.id(), expected);
            return new CompletedClassAttendance(session.id(), session.title(), session.endsAt(), session.timeZone(), expected.size(), joined);
        })).toList();
        return tasks.stream().map(Parallel::join).toList();
    }

    /** Counts for every completed class of the staff member's academy. */
    public List<ClassTotals> classTotals(CurrentAccount caller, Range range) {
        Instant now = DbTime.now();
        List<SessionView> completed = sessions.list(caller, range.start(), range.end(), null).stream()
                .filter(session -> !"CANCELLED".equals(session.status()) && !session.endsAt().isAfter(now))
                .toList();
        List<RosterRequest> requests = completed.stream().map(session -> new RosterRequest(session.id(), session.localDate(),
                session.batches().stream().map(SessionView.Batch::batchId).collect(Collectors.toSet()))).toList();
        Map<UUID, Set<UUID>> expectedByClass = enrollments.expectedStudentIds(caller.academyId(), requests);

        Semaphore permits = new Semaphore(CONCURRENCY);
        List<CompletableFuture<ClassTotals>> tasks = completed.stream().map(session -> Parallel.start(limited(permits, () -> {
            Set<UUID> expected = expectedByClass.getOrDefault(session.id(), Set.of());
            return new ClassTotals(session.id(), expected.size(), facts.countJoined(caller.academyId(), session.id(), expected));
        }))).toList();
        return tasks.stream().map(Parallel::join).toList();
    }

    private record Selected(SessionView session, List<ExpectedStudent> expected, ClassFacts facts) {
    }

    /**
     * Staff see every batch of their academy.
     */
    public Dashboard dashboard(CurrentAccount caller, Range range, UUID courseId, UUID batchId) {
        Instant now = DbTime.now();
        List<SessionView> found = sessions.list(caller, range.start(), range.end(), batchId).stream()
                .filter(s -> !"CANCELLED".equals(s.status()) && s.startsAt().isBefore(now))
                .filter(s -> courseId == null || s.batches().stream().anyMatch(b -> courseId.equals(b.courseId())))
                .sorted(Comparator.comparing(SessionView::startsAt))
                .toList();
        boolean truncated = found.size() > MAX_CLASSES;
        List<SessionView> chosen = truncated ? found.subList(found.size() - MAX_CLASSES, found.size()) : found;

        Semaphore permits = new Semaphore(CONCURRENCY);
        List<CompletableFuture<Selected>> tasks = new ArrayList<>();
        for (SessionView session : chosen) {
            Set<UUID> inSelection = session.batches().stream()
                    .filter(b -> (batchId == null || batchId.equals(b.batchId())) && (courseId == null || courseId.equals(b.courseId())))
                    .map(SessionView.Batch::batchId).collect(Collectors.toSet());
            tasks.add(Parallel.start(limited(permits, () -> {
                List<ExpectedStudent> expected = sessions.expectedStudents(caller, session).stream()
                        .filter(e -> e.batchIds().stream().anyMatch(inSelection::contains)).toList();
                return new Selected(session, expected, facts.factsFor(caller.academyId(), session.id(), session.startsAt(), session.endsAt()));
            })));
        }
        List<Selected> selected = tasks.stream().map(Parallel::join).toList();

        List<ClassSummary> classes = new ArrayList<>();
        Map<UUID, StudentAccumulator> byStudent = new LinkedHashMap<>();
        int connectedNow = 0;
        for (int index = 0; index < selected.size(); index++) {
            Selected item = selected.get(index);
            SessionView session = item.session();
            long classSeconds = heldSeconds(session.startsAt(), session.endsAt(), now);
            Map<UUID, String> batchCode = session.batches().stream()
                    .collect(Collectors.toMap(SessionView.Batch::batchId, SessionView.Batch::code, (a, b) -> a));
            int joined = 0;
            long percentSum = 0;
            for (ExpectedStudent student : item.expected()) {
                Cell cell = cell(item.facts(), student.studentId(), classSeconds);
                joined += cell.visits() > 0 ? 1 : 0;
                percentSum += cell.percent();
                connectedNow += cell.connectedNow() ? 1 : 0;
                StudentAccumulator acc = byStudent.computeIfAbsent(student.studentId(),
                        id -> new StudentAccumulator(id, student.studentNumber(), student.fullName(), selected.size()));
                student.batchIds().stream().map(batchCode::get).filter(java.util.Objects::nonNull).forEach(acc.batchCodes::add);
                acc.add(index, cell, classSeconds);
            }
            List<String> codes = session.batches().stream().map(SessionView.Batch::code).sorted().toList();
            classes.add(new ClassSummary(session.id(), session.title(), session.startsAt(), session.endsAt(), session.timeZone(), codes,
                    classSeconds, item.facts().state(), item.expected().size(), joined,
                    item.expected().isEmpty() ? 0 : (int) Math.round((double) percentSum / item.expected().size())));
        }
        Integer minPercent = policies.find(caller.academyId()).map(AttendancePolicyService.PolicyView::minConnectedPercent).orElse(null);
        List<StudentSummary> rows = byStudent.values().stream().map(StudentAccumulator::summary)
                .sorted(Comparator.comparing(StudentSummary::studentNumber)).toList();
        int average = rows.isEmpty() ? 0 : (int) Math.round(rows.stream().mapToInt(StudentSummary::percent).average().orElse(0));
        int below = minPercent == null ? 0 : (int) rows.stream().filter(r -> r.classes() > 0 && r.percent() < minPercent).count();
        long totalClassSeconds = classes.stream().mapToLong(ClassSummary::classSeconds).sum();
        return new Dashboard(range.from(), range.to(), courseId, batchId, truncated,
                new Totals(classes.size(), rows.size(), average, totalClassSeconds, below, minPercent, connectedNow), classes, rows);
    }

    private static Cell cell(ClassFacts classFacts, UUID studentId, long classSeconds) {
        StudentFacts seen = classFacts.students().get(studentId);
        FinalOutcome finalOutcome = classFacts.finals().get(studentId);
        long connected = finalOutcome != null ? finalOutcome.connectedSeconds() : seen == null ? 0 : seen.connectedSeconds();
        int visits = seen == null ? 0 : seen.visits();
        boolean now = seen != null && "LIVE".equals(classFacts.state()) && seen.openVisits() > 0;
        return new Cell(connected, percent(connected, classSeconds), visits, finalOutcome == null ? null : finalOutcome.outcome(), now);
    }

    /** How much of a class has been held: all of it once over, the elapsed part while it runs (a live Zoom run's class ends
     * at the longest a class may last until Zoom says it ended, so its full length would understate everyone). */
    static long heldSeconds(Instant startsAt, Instant endsAt, Instant now) {
        Instant end = now.isBefore(endsAt) ? now : endsAt;
        return Math.max(1, Duration.between(startsAt, end).toSeconds());
    }

    static int percent(long connected, long total) {
        return total <= 0 ? 0 : (int) Math.min(100, Math.round(connected * 100.0 / total));
    }

    private static final class StudentAccumulator {
        final UUID id;
        final String number;
        final String name;
        final Set<String> batchCodes = new TreeSet<>();
        final Cell[] cells;
        int classes;
        int joined;
        long connected;
        long classSeconds;
        int present;
        int late;
        int absent;
        int excused;
        int rejoins;

        StudentAccumulator(UUID id, String number, String name, int classCount) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.cells = new Cell[classCount];
        }

        void add(int index, Cell cell, long seconds) {
            cells[index] = cell;
            classes++;
            joined += cell.visits() > 0 ? 1 : 0;
            rejoins += Math.max(0, cell.visits() - 1);
            connected += cell.connectedSeconds();
            classSeconds += seconds;
            if (cell.outcome() != null) {
                switch (cell.outcome()) {
                    case "PRESENT" -> present++;
                    case "LATE" -> late++;
                    case "ABSENT" -> absent++;
                    default -> excused++;
                }
            }
        }

        StudentSummary summary() {
            return new StudentSummary(id, number, name, List.copyOf(batchCodes), classes, joined, connected, classSeconds,
                    percent(connected, classSeconds), present, late, absent, excused, rejoins, java.util.Arrays.asList(cells));
        }
    }

    // ---- One student (staff), and a student's own ----

    public record Enrolment(UUID batchId, String batchCode, String batchName, UUID courseId, String courseCode, String courseTitle,
                            LocalDate enrolledOn, LocalDate withdrawnOn, String status) {
    }

    /** One class of the student, with every connection. */
    public record ClassAttendance(UUID id, String title, Instant startsAt, Instant endsAt, String timeZone, List<String> batchCodes,
                                  long classSeconds, String state, long connectedSeconds, int percent, int visits, int rejoins,
                                  boolean connectedNow, Instant firstJoinAt, Long secondsAfterStart, String outcome, List<Visit> timeline) {
    }

    /** Totals over the student's classes in one batch (or overall when {@code batchId} is {@code null}). */
    public record BatchTotals(UUID batchId, String batchCode, String courseCode, int classes, int joined, long connectedSeconds,
                              long classSeconds, int percent, int present, int late, int absent, int excused, int rejoins) {
    }

    public record StudentOverview(UUID studentId, String studentNumber, String fullName, LocalDate from, LocalDate to,
                                  Integer minPercent, BatchTotals overall, List<BatchTotals> byBatch, List<Enrolment> enrolments,
                                  List<ClassAttendance> classes) {
    }

    /** Staff: one student's courses, batches and every class in the range. */
    public StudentOverview forStudent(CurrentAccount caller, UUID studentId, Range range) {
        StudentView student = students.get(caller, studentId); // staff only; 404 outside the academy
        var enrolled = Parallel.start(() -> enrollments.forStudent(caller, studentId));
        List<StudentClassView> classes = sessions.forStudent(caller, studentId, range.start(), range.end());
        return overview(caller.academyId(), student.id(), student.studentNumber(), student.fullName(), range,
                Parallel.join(enrolled), classes);
    }

    /** The signed-in student's own attendance; nobody else's can be asked for. */
    public StudentOverview mine(CurrentAccount caller, Range range) {
        caller.requireRole("STUDENT");
        StudentView me = students.mine(caller);
        var enrolled = Parallel.start(() -> enrollments.mine(caller));
        List<StudentClassView> classes = sessions.mine(caller, range.start(), range.end());
        if (me == null) {
            throw new ResourceNotFoundException("Student record");
        }
        return overview(caller.academyId(), me.id(), me.studentNumber(), me.fullName(), range, Parallel.join(enrolled), classes);
    }

    private StudentOverview overview(UUID academyId, UUID studentId, String number, String name, Range range,
                                     List<EnrollmentView> enrolled, List<StudentClassView> found) {
        Instant now = DbTime.now();
        List<StudentClassView> classes = found.stream()
                .filter(c -> !"CANCELLED".equals(c.status()) && c.startsAt().isBefore(now)).toList();
        if (classes.size() > MAX_CLASSES) {
            classes = classes.subList(classes.size() - MAX_CLASSES, classes.size());
        }
        Semaphore permits = new Semaphore(CONCURRENCY);
        List<CompletableFuture<ClassFacts>> tasks = classes.stream()
                .map(c -> Parallel.start(limited(permits, () -> facts.factsFor(academyId, c.id(), c.startsAt(), c.endsAt())))).toList();
        var courseInfo = Parallel.start(() -> courses(academyId, enrolled.stream().map(EnrollmentView::courseId).collect(Collectors.toSet())));

        List<ClassAttendance> rows = new ArrayList<>();
        Map<UUID, BatchAcc> perBatch = new LinkedHashMap<>();
        BatchAcc overall = new BatchAcc(null, null);
        Map<UUID, EnrollmentView> enrolmentByBatch = enrolled.stream()
                .collect(Collectors.toMap(EnrollmentView::batchId, e -> e, (a, b) -> a));
        for (int i = 0; i < classes.size(); i++) {
            StudentClassView c = classes.get(i);
            ClassFacts classFacts = Parallel.join(tasks.get(i));
            long classSeconds = heldSeconds(c.startsAt(), c.endsAt(), now);
            StudentFacts seen = classFacts.students().get(studentId);
            FinalOutcome finalOutcome = classFacts.finals().get(studentId);
            long connected = finalOutcome != null ? finalOutcome.connectedSeconds() : seen == null ? 0 : seen.connectedSeconds();
            int visits = seen == null ? 0 : seen.visits();
            String outcome = finalOutcome == null ? null : finalOutcome.outcome();
            rows.add(new ClassAttendance(c.id(), c.title(), c.startsAt(), c.endsAt(), c.timeZone(),
                    c.batches().stream().map(StudentClassView.Batch::code).toList(), classSeconds, classFacts.state(), connected,
                    percent(connected, classSeconds), visits, Math.max(0, visits - 1),
                    seen != null && "LIVE".equals(classFacts.state()) && seen.openVisits() > 0,
                    seen == null ? null : seen.firstJoinAt(), seen == null ? null : seen.secondsAfterStart(), outcome,
                    seen == null ? List.of() : seen.timeline()));
            overall.add(classSeconds, connected, visits, outcome);
            for (StudentClassView.Batch b : c.batches()) {
                perBatch.computeIfAbsent(b.batchId(), id -> new BatchAcc(id, b.code())).add(classSeconds, connected, visits, outcome);
            }
        }
        Map<UUID, String[]> courseById = Parallel.join(courseInfo);
        List<Enrolment> enrolments = enrolled.stream().map(e -> {
            String[] course = courseById.getOrDefault(e.courseId(), new String[] {null, null});
            return new Enrolment(e.batchId(), e.batchCode(), e.batchName(), e.courseId(), course[0], course[1], e.enrolledOn(),
                    e.withdrawnOn(), e.status());
        }).sorted(Comparator.comparing(Enrolment::status).thenComparing(Enrolment::batchCode)).toList();
        List<BatchTotals> byBatch = perBatch.values().stream().map(acc -> {
            EnrollmentView e = enrolmentByBatch.get(acc.batchId);
            String courseCode = e == null ? null : courseById.getOrDefault(e.courseId(), new String[] {null, null})[0];
            return acc.totals(courseCode);
        }).sorted(Comparator.comparing(BatchTotals::batchCode)).toList();
        rows.sort(Comparator.comparing(ClassAttendance::startsAt).reversed());
        Integer minPercent = policies.find(academyId).map(AttendancePolicyService.PolicyView::minConnectedPercent).orElse(null);
        return new StudentOverview(studentId, number, name, range.from(), range.to(), minPercent, overall.totals(null), byBatch,
                enrolments, rows);
    }

    private static final class BatchAcc {
        final UUID batchId;
        final String code;
        int classes;
        int joined;
        long connected;
        long classSeconds;
        int present;
        int late;
        int absent;
        int excused;
        int rejoins;

        BatchAcc(UUID batchId, String code) {
            this.batchId = batchId;
            this.code = code;
        }

        void add(long seconds, long connectedSeconds, int visits, String outcome) {
            classes++;
            classSeconds += seconds;
            connected += connectedSeconds;
            joined += visits > 0 ? 1 : 0;
            rejoins += Math.max(0, visits - 1);
            if (outcome != null) {
                switch (outcome) {
                    case "PRESENT" -> present++;
                    case "LATE" -> late++;
                    case "ABSENT" -> absent++;
                    default -> excused++;
                }
            }
        }

        BatchTotals totals(String courseCode) {
            return new BatchTotals(batchId, code, courseCode, classes, joined, connected, classSeconds, percent(connected, classSeconds),
                    present, late, absent, excused, rejoins);
        }
    }

    /** Course code and title by id, for the enrolment list (read here: the student and their own staff may see them). */
    private Map<UUID, String[]> courses(UUID academyId, Set<UUID> ids) {
        Map<UUID, String[]> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.query("SELECT id, code, title FROM courses WHERE academy_id = ? AND id = ANY (?)",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> found.put((UUID) r.getObject(1),
                        new String[] {r.getString(2), r.getString(3)}), academyId, ids.toArray(UUID[]::new));
        return found;
    }

    private static <T> Supplier<T> limited(Semaphore permits, Supplier<T> work) {
        return () -> {
            permits.acquireUninterruptibly();
            try {
                return work.get();
            } finally {
                permits.release();
            }
        };
    }
}
