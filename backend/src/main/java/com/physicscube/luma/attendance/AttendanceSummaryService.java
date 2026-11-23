package com.physicscube.luma.attendance;

import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attendance over a period, from final records only: per student for one batch (staff who may read the batch), and
 * per batch for the whole academy without names (staff).
 *
 * <p>A record counts for a batch when its class served that batch and the student is enrolled in it. A class's date is
 * its date in its own time zone. Rate = (present + late) / (present + late + absent); excused classes are left out.
 */
@Service
public class AttendanceSummaryService {
    static final int MAX_DAYS = 366;
    static final String METHOD = "Final attendance only. Rate = (present + late) / (present + late + absent); excused classes "
            + "are left out. Classes that are not final yet are counted separately.";

    private final JdbcTemplate jdbc;
    private final BatchService batches;

    AttendanceSummaryService(JdbcTemplate jdbc, BatchService batches) {
        this.jdbc = jdbc;
        this.batches = batches;
    }

    public record Counts(int present, int late, int absent, int excused, Integer ratePercent) {
        static Counts of(int present, int late, int absent, int excused) {
            int counted = present + late + absent;
            return new Counts(present, late, absent, excused, counted == 0 ? null : Math.round(100f * (present + late) / counted));
        }
    }

    public record StudentSummary(UUID studentId, String studentNumber, String fullName, Counts counts) {
    }

    public record BatchSummary(LocalDate from, LocalDate to, int finalClasses, int classesNotFinal, Counts totals,
                               List<StudentSummary> students, String method) {
    }

    public record BatchTotals(UUID batchId, String batchCode, String batchName, String courseCode, String courseTitle,
                              int finalClasses, int classesNotFinal, Counts totals) {
    }

    public record AcademySummary(LocalDate from, LocalDate to, List<BatchTotals> batches, String method) {
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new InvalidRequestException("from and to are required, to not before from, at most " + MAX_DAYS + " days");
        }
    }

    private static final String IN_RANGE = """
            cs.status <> 'CANCELLED' AND (cs.starts_at AT TIME ZONE cs.time_zone)::date BETWEEN ? AND ?
            AND NOT EXISTS (SELECT 1 FROM zoom_classes_without_attendees z
                            WHERE z.academy_id = cs.academy_id AND z.class_session_id = cs.id)
            """;

    /** Each enrolled student's final attendance in one batch's classes. */
    @Transactional(readOnly = true)
    public BatchSummary forBatch(CurrentAccount caller, UUID batchId, LocalDate from, LocalDate to) {
        batches.requireReadable(caller, batchId);
        checkRange(from, to);
        UUID academy = caller.academyId();
        Map<UUID, int[]> byStudent = new LinkedHashMap<>();
        Map<UUID, String[]> names = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.id, s.student_number, s.full_name, ar.outcome, count(*) AS n
                FROM attendance_records ar
                JOIN class_sessions cs ON cs.id = ar.class_session_id AND cs.academy_id = ar.academy_id
                JOIN session_batches sb ON sb.session_id = cs.id AND sb.batch_id = ?
                JOIN enrollments e ON e.batch_id = sb.batch_id AND e.student_id = ar.student_id
                JOIN students s ON s.id = ar.student_id
                WHERE ar.academy_id = ? AND\s""" + IN_RANGE + """

                GROUP BY s.id, s.student_number, s.full_name, ar.outcome
                """, row -> {
            UUID id = row.getObject("id", UUID.class);
            names.putIfAbsent(id, new String[] {row.getString("student_number"), row.getString("full_name")});
            add(byStudent.computeIfAbsent(id, k -> new int[4]), row.getString("outcome"), row.getInt("n"));
        }, batchId, academy, from, to);
        int[] total = new int[4];
        List<StudentSummary> students = new ArrayList<>();
        byStudent.forEach((id, c) -> {
            for (int i = 0; i < 4; i++) {
                total[i] += c[i];
            }
            students.add(new StudentSummary(id, names.get(id)[0], names.get(id)[1], Counts.of(c[0], c[1], c[2], c[3])));
        });
        // Lowest rate first: the students staff most need to see.
        students.sort(Comparator.comparing((StudentSummary s) -> s.counts().ratePercent() == null ? Integer.MAX_VALUE : s.counts().ratePercent())
                .thenComparing(StudentSummary::studentNumber));
        int[] classes = classCounts("sb.batch_id = ?", batchId, academy, from, to).getOrDefault(batchId, new int[2]);
        return new BatchSummary(from, to, classes[0], classes[1], Counts.of(total[0], total[1], total[2], total[3]), students, METHOD);
    }

    /** Totals per batch for the academy, without student names. */
    @Transactional(readOnly = true)
    public AcademySummary forAcademy(CurrentAccount caller, LocalDate from, LocalDate to) {
        caller.requireStaff();
        checkRange(from, to);
        UUID academy = caller.academyId();
        Map<UUID, int[]> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sb.batch_id, ar.outcome, count(*) AS n
                FROM attendance_records ar
                JOIN class_sessions cs ON cs.id = ar.class_session_id AND cs.academy_id = ar.academy_id
                JOIN session_batches sb ON sb.session_id = cs.id
                JOIN enrollments e ON e.batch_id = sb.batch_id AND e.student_id = ar.student_id
                WHERE ar.academy_id = ? AND\s""" + IN_RANGE + """

                GROUP BY sb.batch_id, ar.outcome
                """, (org.springframework.jdbc.core.RowCallbackHandler) row -> add(counts.computeIfAbsent(row.getObject("batch_id", UUID.class),
                k -> new int[4]), row.getString("outcome"), row.getInt("n")), academy, from, to);
        Map<UUID, int[]> classes = classCounts("sb.academy_id = ?", academy, academy, from, to);
        List<BatchTotals> result = jdbc.query("""
                SELECT b.id, b.code, b.name, c.code AS course_code, c.title
                FROM batches b JOIN courses c ON c.id = b.course_id
                WHERE b.academy_id = ?
                ORDER BY c.code, b.code
                """, (row, i) -> {
            UUID id = row.getObject("id", UUID.class);
            int[] c = counts.getOrDefault(id, new int[4]);
            int[] k = classes.getOrDefault(id, new int[2]);
            return new BatchTotals(id, row.getString("code"), row.getString("name"), row.getString("course_code"), row.getString("title"),
                    k[0], k[1], Counts.of(c[0], c[1], c[2], c[3]));
        }, academy).stream().filter(b -> b.finalClasses() + b.classesNotFinal() > 0).toList();
        return new AcademySummary(from, to, result, METHOD);
    }

    /** Per batch: classes in range that are final, and classes that have ended but are not final yet. */
    private Map<UUID, int[]> classCounts(String batchFilter, UUID filterValue, UUID academy, LocalDate from, LocalDate to) {
        Map<UUID, int[]> result = new LinkedHashMap<>();
        jdbc.query("SELECT sb.batch_id, "
                + "count(*) FILTER (WHERE EXISTS (SELECT 1 FROM attendance_records ar WHERE ar.class_session_id = cs.id)) AS final, "
                + "count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM attendance_records ar WHERE ar.class_session_id = cs.id) "
                + "AND cs.ends_at < now()) AS waiting "
                + "FROM class_sessions cs JOIN session_batches sb ON sb.session_id = cs.id "
                + "WHERE " + batchFilter + " AND cs.academy_id = ? AND " + IN_RANGE + " GROUP BY sb.batch_id",
                row -> { result.put(row.getObject("batch_id", UUID.class), new int[] {row.getInt("final"), row.getInt("waiting")}); },
                filterValue, academy, from, to);
        return result;
    }

    private static void add(int[] counts, String outcome, int n) {
        switch (outcome) {
            case "PRESENT" -> counts[0] += n;
            case "LATE" -> counts[1] += n;
            case "ABSENT" -> counts[2] += n;
            case "EXCUSED" -> counts[3] += n;
            default -> throw new IllegalStateException("Unknown outcome " + outcome);
        }
    }
}
