package com.physicscube.luma.operations;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases a student's personal data on request, in one transaction (owners only; audited without
 * personal data). The student record stays, anonymised, so classes and attendance figures remain
 * consistent: name and number are replaced, the sign-in account is disabled with its email replaced,
 * and every Zoom name, email and registration linked to the student's known emails is removed,
 * together with engagement data, consent and appeal texts. The audit log is append-only by design
 * and keeps who did what; it holds Zoom emails only for identity decisions (documented).
 */
@Service
public class StudentErasureService {
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    StudentErasureService(JdbcTemplate jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ErasureResult(UUID studentId, String placeholderNumber, int zoomRowsCleared) {
    }

    /** @param confirmNumber the student's current number, typed again to confirm */
    @Transactional
    public ErasureResult erase(CurrentAccount caller, UUID studentId, String confirmNumber, String reason) {
        if (!caller.hasRole("ACADEMY_OWNER")) {
            throw new AccessDeniedException("Only an academy owner erases a student's data");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 300) {
            throw new InvalidRequestException("A reason of 3 to 300 characters is required");
        }
        List<Map<String, Object>> found = jdbc.queryForList("SELECT s.student_number, s.account_id, a.email FROM students s "
                + "JOIN accounts a ON a.id = s.account_id WHERE s.id = ? AND s.academy_id = ? FOR UPDATE OF s, a", studentId, caller.academyId());
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Student");
        }
        String number = (String) found.get(0).get("student_number");
        if (confirmNumber == null || !number.equalsIgnoreCase(confirmNumber.strip())) {
            throw new InvalidRequestException("Type the student's number to confirm");
        }
        UUID accountId = (UUID) found.get(0).get("account_id");
        // Every email this person is known by: sign-in, staff decisions, and registrations of those.
        List<String> emails = new ArrayList<>();
        emails.add((String) found.get(0).get("email"));
        emails.addAll(jdbc.queryForList("SELECT email FROM zoom_identity_links WHERE academy_id = ? AND student_id = ?", String.class,
                caller.academyId(), studentId));
        String[] known = emails.stream().distinct().toArray(String[]::new);
        String placeholder = "ERASED-" + studentId.toString().substring(0, 8).toUpperCase();
        Timestamp now = Timestamp.from(DbTime.now());

        jdbc.update("UPDATE students SET full_name = 'Erased student', student_number = ?, active = FALSE, updated_at = ?, version = version + 1 "
                + "WHERE id = ?", placeholder, now, studentId);
        jdbc.update("UPDATE accounts SET email = ?, password_hash = NULL, status = 'DISABLED', auth_version = auth_version + 1 WHERE id = ?",
                "erased-" + accountId + "@erased.invalid", accountId);
        jdbc.update("UPDATE refresh_sessions SET revoked_at = ?, revoke_reason = 'LOGOUT_ALL' WHERE account_id = ? AND revoked_at IS NULL",
                now, accountId);
        for (String table : List.of("account_totp", "account_recovery_codes", "account_invitations", "password_resets")) {
            jdbc.update("DELETE FROM " + table + " WHERE account_id = ?", accountId);
        }

        int cleared = 0;
        cleared += jdbc.update("UPDATE zoom_occurrence_events SET participant_email = NULL, participant_name = NULL, registrant_id = NULL "
                + "WHERE academy_id = ? AND participant_email = ANY (?)", caller.academyId(), known);
        cleared += jdbc.update("""
                UPDATE zoom_webhook_receipts SET payload = payload #- '{payload,object,participant,email}' #- '{payload,object,participant,user_name}'
                  #- '{payload,object,participant,registrant_id}'
                WHERE academy_id = ? AND lower(payload #>> '{payload,object,participant,email}') = ANY (?)
                """, caller.academyId(), known);
        cleared += jdbc.update("UPDATE zoom_report_participants SET email = NULL, display_name = NULL, registrant_id = NULL, registrant_email = NULL "
                + "WHERE academy_id = ? AND (email = ANY (?) OR registrant_email = ANY (?))", caller.academyId(), known, known);
        cleared += jdbc.update("DELETE FROM zoom_registrants WHERE academy_id = ? AND email = ANY (?)", caller.academyId(), known);
        jdbc.update("DELETE FROM zoom_identity_links WHERE academy_id = ? AND (student_id = ? OR email = ANY (?))", caller.academyId(), studentId, known);
        jdbc.update("DELETE FROM portal_reactions WHERE academy_id = ? AND student_id = ?", caller.academyId(), studentId);
        jdbc.update("DELETE FROM pulse_answers WHERE academy_id = ? AND student_id = ?", caller.academyId(), studentId);
        jdbc.update("DELETE FROM engagement_consents WHERE academy_id = ? AND student_id = ?", caller.academyId(), studentId);
        jdbc.update("UPDATE attendance_appeals SET message = '[erased]' WHERE academy_id = ? AND student_id = ?", caller.academyId(), studentId);
        jdbc.update("DELETE FROM invitation_emails WHERE account_id = ?", accountId);

        audit.record(caller.academyId(), caller.accountId(), "STUDENT_ERASED", "STUDENT", studentId, Map.of(
                "placeholder", placeholder, "zoomRowsCleared", String.valueOf(cleared), "reason", why));
        return new ErasureResult(studentId, placeholder, cleared);
    }
}
