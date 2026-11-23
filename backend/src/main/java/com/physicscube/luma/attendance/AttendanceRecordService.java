package com.physicscube.luma.attendance;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.students.StudentService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes to final attendance (slice 004C). Every change is a correction with a reason, kept in an
 * append-only history and the audit trail; the computed outcome is never overwritten. Staff
 * correct; a student appeals their own record, and staff accept (which corrects it) or reject.
 */
@Service
public class AttendanceRecordService {
    static final Set<String> OUTCOMES = Set.of("PRESENT", "LATE", "ABSENT", "EXCUSED");

    private final JdbcTemplate jdbc;
    private final StudentService students;
    private final AuditLog audit;
    private final com.physicscube.luma.identity.AccountNotifier notifier;

    AttendanceRecordService(JdbcTemplate jdbc, StudentService students, AuditLog audit,
                            com.physicscube.luma.identity.AccountNotifier notifier) {
        this.jdbc = jdbc;
        this.students = students;
        this.audit = audit;
        this.notifier = notifier;
    }

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);

    /** The record's class and the student's account, for notices. */
    private record Context(UUID studentAccountId, String classTitle, String classDay) {
    }

    private Context context(UUID recordId) {
        return jdbc.query("""
                SELECT st.account_id, s.title, s.starts_at, s.time_zone FROM attendance_records r
                JOIN students st ON st.id = r.student_id AND st.academy_id = r.academy_id
                JOIN class_sessions s ON s.id = r.class_session_id AND s.academy_id = r.academy_id WHERE r.id = ?
                """, (row, i) -> new Context((UUID) row.getObject(1), row.getString(2),
                DAY.format(row.getTimestamp(3).toInstant().atZone(java.time.ZoneId.of(row.getString(4))))), recordId).get(0);
    }

    private String portalLine(String path) {
        return notifier.webOrigin() == null ? "" : "\n\nOpen LUMA: " + notifier.webOrigin() + path;
    }

    private static String label(String outcome) {
        return outcome.charAt(0) + outcome.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    public record Correction(UUID id, String previousOutcome, String newOutcome, String reason, UUID appealId, Instant correctedAt) {
    }

    /** A final record with its class, as listed in a student's history. */
    public record RecordView(UUID id, UUID classSessionId, String classTitle, Instant classStartsAt, Instant classEndsAt, String timeZone,
                             String computedOutcome, String outcome, long connectedSeconds, Instant firstJoinAt, Integer secondsAfterStart,
                             Instant finalisedAt, long version, String appealStatus) {
    }

    public record Appeal(UUID id, UUID recordId, UUID studentId, String studentNumber, String studentName, String classTitle,
                         Instant classStartsAt, String timeZone, String outcome, String message, String status, Instant createdAt,
                         String resolutionNote, Instant resolvedAt) {
    }

    private record Owned(UUID id, UUID sessionId, UUID studentId, String outcome, long version) {
    }

    // ---- corrections ----------------------------------------------------------------------

    @Transactional
    public RecordView correct(CurrentAccount caller, UUID recordId, String outcome, String reason, long expectedVersion) {
        Owned record = requireCorrectable(caller, recordId);
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 500) {
            throw new InvalidRequestException("A reason of 3 to 500 characters is required");
        }
        if (record.version() != expectedVersion) {
            throw new ConflictException("This record was changed by someone else; reload and try again");
        }
        apply(caller, record, outcome, why, null);
        return view(caller.academyId(), recordId);
    }

    @Transactional(readOnly = true)
    public List<Correction> corrections(CurrentAccount caller, UUID recordId) {
        requireReadable(caller, recordId);
        return jdbc.query("SELECT id, previous_outcome, new_outcome, reason, appeal_id, corrected_at FROM attendance_corrections "
                + "WHERE academy_id = ? AND record_id = ? ORDER BY corrected_at, id", (row, i) -> new Correction((UUID) row.getObject(1),
                row.getString(2), row.getString(3), row.getString(4), (UUID) row.getObject(5), row.getTimestamp(6).toInstant()),
                caller.academyId(), recordId);
    }

    // ---- history ----------------------------------------------------------------------------

    /** A student's final records, newest class first. Staff. */
    @Transactional(readOnly = true)
    public List<RecordView> studentHistory(CurrentAccount caller, UUID studentId) {
        caller.requireStaff();
        students.requireInAcademy(caller.academyId(), studentId);
        return history(caller.academyId(), studentId);
    }

    /** The signed-in student's own final records. */
    @Transactional(readOnly = true)
    public List<RecordView> mine(CurrentAccount caller) {
        return history(caller.academyId(), ownStudent(caller));
    }

    // ---- appeals ----------------------------------------------------------------------------

    @Transactional
    public Appeal appeal(CurrentAccount caller, UUID recordId, String message) {
        UUID studentId = ownStudent(caller);
        String text = message == null ? "" : message.strip();
        if (text.length() < 10 || text.length() > 1000) {
            throw new InvalidRequestException("Explain the appeal in 10 to 1000 characters");
        }
        List<Owned> found = jdbc.query("SELECT id, class_session_id, student_id, outcome, version FROM attendance_records "
                + "WHERE id = ? AND academy_id = ? AND student_id = ? FOR UPDATE", (row, i) -> owned(row), recordId, caller.academyId(), studentId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Attendance record");
        }
        Integer open = jdbc.queryForObject("SELECT count(*) FROM attendance_appeals WHERE record_id = ? AND status = 'OPEN'", Integer.class, recordId);
        if (open != null && open > 0) {
            throw new ConflictException("An appeal for this class is already waiting for a decision");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO attendance_appeals (id, academy_id, record_id, student_id, message, status, created_at) VALUES (?, ?, ?, ?, ?, 'OPEN', ?)",
                id, caller.academyId(), recordId, studentId, text, Timestamp.from(DbTime.now()));
        Context appealed = context(recordId);
        for (UUID staff : notifier.activeStaff(caller.academyId())) {
            notifier.notify(staff, "appeal-submitted", "A student appealed their attendance",
                    "A student appealed their attendance for " + appealed.classTitle() + " on " + appealed.classDay()
                            + ". Review it on the Attendance page." + portalLine("/staff/attendance"));
        }
        audit.record(caller.academyId(), caller.accountId(), "ATTENDANCE_APPEAL_SUBMITTED", "ATTENDANCE_RECORD", recordId,
                Map.of("outcome", found.get(0).outcome()));
        return appealView(caller.academyId(), id);
    }

    /** @param status OPEN, ACCEPTED, REJECTED or {@code null} for all; newest first. Staff. */
    @Transactional(readOnly = true)
    public List<Appeal> appeals(CurrentAccount caller, String status) {
        caller.requireStaff();
        if (status != null && !Set.of("OPEN", "ACCEPTED", "REJECTED").contains(status)) {
            throw new InvalidRequestException("status must be OPEN, ACCEPTED or REJECTED");
        }
        return jdbc.query(APPEAL_SQL + " WHERE a.academy_id = ? AND (?::text IS NULL OR a.status = ?) ORDER BY a.created_at DESC LIMIT 200",
                (row, i) -> appeal(row), caller.academyId(), status, status);
    }

    /** Accepting corrects the record to {@code outcome}; rejecting needs a note for the student. */
    @Transactional
    public Appeal resolve(CurrentAccount caller, UUID appealId, boolean accept, String outcome, String note) {
        caller.requireStaff();
        List<Map<String, Object>> found = jdbc.queryForList("SELECT record_id, status FROM attendance_appeals WHERE id = ? AND academy_id = ? FOR UPDATE",
                appealId, caller.academyId());
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Appeal");
        }
        if (!"OPEN".equals(found.get(0).get("status"))) {
            throw new ConflictException("This appeal has already been decided");
        }
        String text = note == null ? "" : note.strip();
        if (text.length() < 3 || text.length() > 500) {
            throw new InvalidRequestException("A note of 3 to 500 characters is required; the student sees it");
        }
        UUID recordId = (UUID) found.get(0).get("record_id");
        if (accept) {
            Owned record = jdbc.query("SELECT id, class_session_id, student_id, outcome, version FROM attendance_records WHERE id = ? FOR UPDATE",
                    (row, i) -> owned(row), recordId).get(0);
            apply(caller, record, outcome, "Appeal accepted: " + text, appealId);
        }
        jdbc.update("UPDATE attendance_appeals SET status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ? WHERE id = ?",
                accept ? "ACCEPTED" : "REJECTED", caller.accountId(), Timestamp.from(DbTime.now()), text, appealId);
        if (!accept) {
            Context c = context(recordId);
            notifier.notify(c.studentAccountId(), "appeal-decided", "Your attendance appeal was not accepted",
                    "Your appeal about " + c.classTitle() + " on " + c.classDay() + " was not accepted.\nNote from the academy: " + text
                            + portalLine("/student"));
        }
        audit.record(caller.academyId(), caller.accountId(), accept ? "ATTENDANCE_APPEAL_ACCEPTED" : "ATTENDANCE_APPEAL_REJECTED",
                "ATTENDANCE_RECORD", recordId, Map.of("note", text));
        return appealView(caller.academyId(), appealId);
    }

    // ---- internals --------------------------------------------------------------------------

    private void apply(CurrentAccount caller, Owned record, String outcome, String reason, UUID appealId) {
        String next = outcome == null ? "" : outcome.strip().toUpperCase(java.util.Locale.ROOT);
        if (!OUTCOMES.contains(next)) {
            throw new InvalidRequestException("outcome must be PRESENT, LATE, ABSENT or EXCUSED");
        }
        if (next.equals(record.outcome())) {
            throw new InvalidRequestException("The record already says " + next);
        }
        jdbc.update("""
                INSERT INTO attendance_corrections (id, academy_id, record_id, previous_outcome, new_outcome, reason, appeal_id, corrected_by, corrected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), caller.academyId(), record.id(), record.outcome(), next, reason, appealId, caller.accountId(),
                Timestamp.from(DbTime.now()));
        jdbc.update("UPDATE attendance_records SET outcome = ?, version = version + 1 WHERE id = ?", next, record.id());
        Context c = context(record.id());
        notifier.notify(c.studentAccountId(), "attendance-corrected", "Your attendance was updated",
                "Your attendance for " + c.classTitle() + " on " + c.classDay() + " is now " + label(next) + " (it was "
                        + label(record.outcome()) + ").\nReason: " + reason + portalLine("/student"));
        audit.record(caller.academyId(), caller.accountId(), "ATTENDANCE_CORRECTED", "CLASS_SESSION", record.sessionId(), Map.of(
                "recordId", record.id().toString(), "from", record.outcome(), "to", next, "reason", reason));
    }

    /** Staff of the record's academy. Not found otherwise. */
    private Owned requireCorrectable(CurrentAccount caller, UUID recordId) {
        caller.requireStaff();
        Owned record = jdbc.query("SELECT id, class_session_id, student_id, outcome, version FROM attendance_records WHERE id = ? AND academy_id = ? "
                + "FOR UPDATE", (row, i) -> owned(row), recordId, caller.academyId()).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record"));
        return record;
    }

    private void requireReadable(CurrentAccount caller, UUID recordId) {
        caller.requireStaff();
        jdbc.query("SELECT id, class_session_id, student_id, outcome, version FROM attendance_records WHERE id = ? AND academy_id = ?",
                (row, i) -> owned(row), recordId, caller.academyId()).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Attendance record"));
    }

    private UUID ownStudent(CurrentAccount caller) {
        caller.requireRole("STUDENT");
        return students.idForAccount(caller.academyId(), caller.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Student record"));
    }

    private List<RecordView> history(UUID academyId, UUID studentId) {
        return jdbc.query(RECORD_SQL + """
                 WHERE r.academy_id = ? AND r.student_id = ?
                   AND NOT EXISTS (SELECT 1 FROM zoom_classes_without_attendees z
                                   WHERE z.academy_id = s.academy_id AND z.class_session_id = s.id)
                 ORDER BY s.starts_at DESC LIMIT 500
                """,
                (row, i) -> record(row), academyId, studentId);
    }

    private RecordView view(UUID academyId, UUID recordId) {
        return jdbc.query(RECORD_SQL + " WHERE r.academy_id = ? AND r.id = ?", (row, i) -> record(row), academyId, recordId).get(0);
    }

    private Appeal appealView(UUID academyId, UUID appealId) {
        return jdbc.query(APPEAL_SQL + " WHERE a.academy_id = ? AND a.id = ?", (row, i) -> appeal(row), academyId, appealId).get(0);
    }

    private static final String RECORD_SQL = """
            SELECT r.id, r.class_session_id, s.title, s.starts_at, s.ends_at, s.time_zone, r.computed_outcome, r.outcome, r.connected_seconds,
                   r.first_join_at, r.seconds_after_start, r.finalised_at, r.version,
                   (SELECT a.status FROM attendance_appeals a WHERE a.record_id = r.id ORDER BY a.created_at DESC LIMIT 1) AS appeal_status
            FROM attendance_records r JOIN class_sessions s ON s.id = r.class_session_id AND s.academy_id = r.academy_id
            """;

    private static final String APPEAL_SQL = """
            SELECT a.id, a.record_id, a.student_id, st.student_number, st.full_name, s.title, s.starts_at, s.time_zone, r.outcome, a.message,
                   a.status, a.created_at, a.resolution_note, a.resolved_at
            FROM attendance_appeals a
            JOIN attendance_records r ON r.id = a.record_id AND r.academy_id = a.academy_id
            JOIN class_sessions s ON s.id = r.class_session_id AND s.academy_id = r.academy_id
            JOIN students st ON st.id = a.student_id AND st.academy_id = a.academy_id
            """;

    private static Owned owned(ResultSet row) throws SQLException {
        return new Owned((UUID) row.getObject(1), (UUID) row.getObject(2), (UUID) row.getObject(3), row.getString(4), row.getLong(5));
    }

    private static RecordView record(ResultSet row) throws SQLException {
        Timestamp joined = row.getTimestamp("first_join_at");
        return new RecordView((UUID) row.getObject("id"), (UUID) row.getObject("class_session_id"), row.getString("title"),
                row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant(), row.getString("time_zone"),
                row.getString("computed_outcome"), row.getString("outcome"), row.getLong("connected_seconds"),
                joined == null ? null : joined.toInstant(), (Integer) row.getObject("seconds_after_start"),
                row.getTimestamp("finalised_at").toInstant(), row.getLong("version"), row.getString("appeal_status"));
    }

    private static Appeal appeal(ResultSet row) throws SQLException {
        Timestamp resolved = row.getTimestamp("resolved_at");
        return new Appeal((UUID) row.getObject("id"), (UUID) row.getObject("record_id"), (UUID) row.getObject("student_id"),
                row.getString("student_number"), row.getString("full_name"), row.getString("title"), row.getTimestamp("starts_at").toInstant(),
                row.getString("time_zone"), row.getString("outcome"), row.getString("message"), row.getString("status"),
                row.getTimestamp("created_at").toInstant(), row.getString("resolution_note"), resolved == null ? null : resolved.toInstant());
    }
}
