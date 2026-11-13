package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.ProvisionalAttendanceService.ProvisionalAttendance;
import com.physicscube.luma.attendance.ProvisionalAttendanceService.StudentAttendance;
import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.batches.BatchView;
import com.physicscube.luma.enrollments.ExpectedStudent;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.SessionView;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Attendance facts as CSV (slice 005A): one class, or one batch over a date range. The rows
 * are exactly what the attendance view shows the same caller (same scoping), with the data
 * source on every row. Every export is recorded in the audit trail. Cells that a spreadsheet
 * would run as a formula are prefixed with an apostrophe.
 */
@Service
public class AttendanceExportService {
    static final int MAX_DAYS = 92;
    static final String HEADER = "class_date,class_start,class_end,time_zone,class_title,class_status,data_source,student_number,"
            + "student_name,expected,seen,visits,visits_without_leave,minutes_connected,first_join,minutes_after_start,final_outcome";
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ProvisionalAttendanceService attendance;
    private final ClassSessionService sessions;
    private final BatchService batches;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    AttendanceExportService(ProvisionalAttendanceService attendance, ClassSessionService sessions, BatchService batches, AuditLog audit,
                            TransactionTemplate transactions) {
        this.attendance = attendance;
        this.sessions = sessions;
        this.batches = batches;
        this.audit = audit;
        this.transactions = transactions;
    }

    public record CsvFile(String filename, String content, int rows) {
    }

    public CsvFile forClass(CurrentAccount caller, UUID sessionId) {
        SessionView session = sessions.get(caller, sessionId);
        ProvisionalAttendance result = attendance.forClass(caller, sessionId);
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        int rows = append(csv, session, result, result.students());
        String filename = "attendance-" + session.localDate() + "-" + slug(session.title()) + ".csv";
        record(caller, "CLASS_SESSION", sessionId, Map.of("scope", "class", "rows", String.valueOf(rows), "source", result.status()));
        return new CsvFile(filename, csv.toString(), rows);
    }

    /** Classes of the batch dated {@code from} to {@code to} (inclusive, the class's own date); students of that batch only. */
    public CsvFile forBatch(CurrentAccount caller, UUID batchId, LocalDate from, LocalDate to) {
        BatchView batch = batches.requireReadable(caller, batchId);
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new InvalidRequestException("from and to are required, to not before from, at most " + MAX_DAYS + " days");
        }
        // Wide enough for any time zone; the class's own date decides.
        Instant start = from.atStartOfDay(ZoneOffset.UTC).minusHours(14).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).plusHours(14).toInstant();
        // The timetable lists at most 92 days per request; read the range in 60-day pieces.
        Map<UUID, SessionView> found = new java.util.LinkedHashMap<>();
        for (Instant piece = start; piece.isBefore(end); piece = piece.plus(java.time.Duration.ofDays(60))) {
            Instant pieceEnd = piece.plus(java.time.Duration.ofDays(60)).isBefore(end) ? piece.plus(java.time.Duration.ofDays(60)) : end;
            sessions.list(caller, piece, pieceEnd, batchId).forEach(s -> found.putIfAbsent(s.id(), s));
        }
        List<SessionView> classes = found.values().stream()
                .filter(s -> !s.localDate().isBefore(from) && !s.localDate().isAfter(to))
                .sorted(Comparator.comparing(SessionView::startsAt)).toList();
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        int rows = 0;
        for (SessionView session : classes) {
            Set<UUID> inBatch = sessions.expectedStudents(caller, session.id()).stream()
                    .filter(s -> s.batchIds().contains(batchId)).map(ExpectedStudent::studentId).collect(Collectors.toSet());
            ProvisionalAttendance result = attendance.forClass(caller, session.id());
            rows += append(csv, session, result, result.students().stream().filter(s -> inBatch.contains(s.studentId())).toList());
        }
        String filename = "attendance-" + slug(batch.code()) + "-" + from + "-to-" + to + ".csv";
        record(caller, "BATCH", batchId, Map.of("scope", "batch", "from", from.toString(), "to", to.toString(),
                "classes", String.valueOf(classes.size()), "rows", String.valueOf(rows)));
        return new CsvFile(filename, csv.toString(), rows);
    }

    private static int append(StringBuilder csv, SessionView session, ProvisionalAttendance result, List<StudentAttendance> students) {
        ZoneId zone = ZoneId.of(session.timeZone());
        for (StudentAttendance s : students) {
            csv.append(String.join(",", List.of(
                    cell(session.localDate().toString()),
                    cell(TIME.format(session.startsAt().atZone(zone))),
                    cell(TIME.format(session.endsAt().atZone(zone))),
                    cell(session.timeZone()),
                    cell(session.title()),
                    cell(session.status()),
                    cell(result.status()),
                    cell(s.studentNumber()),
                    cell(s.fullName()),
                    s.expected() ? "yes" : "no",
                    s.seen() ? "yes" : "no",
                    String.valueOf(s.visits()),
                    String.valueOf(s.openVisits()),
                    String.valueOf(s.connectedSeconds() / 60),
                    s.firstJoinAt() == null ? "" : cell(LOCAL.format(s.firstJoinAt().atZone(zone))),
                    s.secondsAfterStart() == null ? "" : String.valueOf(s.secondsAfterStart() / 60),
                    s.outcome() == null ? "" : s.outcome()))).append("\r\n");
        }
        return students.size();
    }

    /** RFC 4180 quoting, and a leading apostrophe for text a spreadsheet would treat as a formula. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r") || text.startsWith("'")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String slug(String text) {
        String slug = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "class" : slug.length() > 40 ? slug.substring(0, 40) : slug;
    }

    private void record(CurrentAccount caller, String targetType, UUID targetId, Map<String, String> details) {
        transactions.executeWithoutResult(status -> audit.record(caller.academyId(), caller.accountId(), "ATTENDANCE_EXPORTED",
                targetType, targetId, details));
    }
}
