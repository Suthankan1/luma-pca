package com.physicscube.luma.attendance;

import com.physicscube.luma.attendance.domain.AttendanceRule;
import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The academy's attendance rule, as its owner records it (OPEN-QUESTIONS P0: the threshold and
 * exceptions are the academy's decision). Staff who see attendance may read it; only an owner
 * may set it, and every change is audited. Finalised records keep the rule they used.
 */
@Service
public class AttendancePolicyService {
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    AttendancePolicyService(JdbcTemplate jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record PolicyView(int minConnectedPercent, int lateAfterMinutes, String description, long version, Instant updatedAt,
                             boolean autoFinalise) {
        public AttendanceRule rule() {
            return new AttendanceRule(minConnectedPercent, lateAfterMinutes);
        }
    }

    @Transactional(readOnly = true)
    public Optional<PolicyView> get(CurrentAccount caller) {
        if (!caller.isAcademyManager() && caller.batchScopedRoles().isEmpty() && !caller.hasRole("ANALYST")) {
            throw new AccessDeniedException("No role that reads attendance");
        }
        return find(caller.academyId());
    }

    /** For this module: the academy's rule, if one has been recorded. */
    Optional<PolicyView> find(UUID academyId) {
        List<PolicyView> found = jdbc.query("SELECT min_connected_percent, late_after_minutes, description, version, updated_at, auto_finalise "
                + "FROM attendance_policies WHERE academy_id = ?", (row, i) -> new PolicyView(row.getInt(1), row.getInt(2),
                row.getString(3), row.getLong(4), row.getTimestamp(5).toInstant(), row.getBoolean(6)), academyId);
        return found.stream().findFirst();
    }

    /** @param expectedVersion {@code null} when setting the first rule */
    @Transactional
    public PolicyView set(CurrentAccount caller, int minConnectedPercent, int lateAfterMinutes, String description, Long expectedVersion) {
        return set(caller, minConnectedPercent, lateAfterMinutes, description, expectedVersion, null);
    }

    /** @param autoFinalise {@code null} keeps the current choice (off for a first rule) */
    @Transactional
    public PolicyView set(CurrentAccount caller, int minConnectedPercent, int lateAfterMinutes, String description, Long expectedVersion,
                          Boolean autoFinalise) {
        if (!caller.hasRole("ACADEMY_OWNER")) {
            throw new AccessDeniedException("Only an academy owner records the attendance rule");
        }
        try {
            new AttendanceRule(minConnectedPercent, lateAfterMinutes);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Connected share must be 1 to 100 percent and lateness 0 to 240 minutes");
        }
        String text = description == null ? "" : description.strip();
        if (text.length() < 3 || text.length() > 500) {
            throw new InvalidRequestException("Describe the approved rule in 3 to 500 characters");
        }
        Optional<PolicyView> before = jdbc.query("SELECT min_connected_percent, late_after_minutes, description, version, updated_at, auto_finalise "
                + "FROM attendance_policies WHERE academy_id = ? FOR UPDATE", (row, i) -> new PolicyView(row.getInt(1), row.getInt(2),
                row.getString(3), row.getLong(4), row.getTimestamp(5).toInstant(), row.getBoolean(6)), caller.academyId()).stream().findFirst();
        boolean auto = autoFinalise != null ? autoFinalise : before.map(PolicyView::autoFinalise).orElse(false);
        long current = before.map(PolicyView::version).orElse(-1L);
        if ((expectedVersion == null && before.isPresent()) || (expectedVersion != null && expectedVersion != current)) {
            throw new ConflictException("The attendance rule was changed by someone else; reload and try again");
        }
        Instant now = DbTime.now();
        jdbc.update("""
                INSERT INTO attendance_policies (academy_id, min_connected_percent, late_after_minutes, description, version, updated_by, updated_at,
                  auto_finalise, auto_finalise_by)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT (academy_id) DO UPDATE SET min_connected_percent = EXCLUDED.min_connected_percent,
                  late_after_minutes = EXCLUDED.late_after_minutes, description = EXCLUDED.description,
                  version = attendance_policies.version + 1, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at,
                  auto_finalise = EXCLUDED.auto_finalise, auto_finalise_by = EXCLUDED.auto_finalise_by
                """, caller.academyId(), minConnectedPercent, lateAfterMinutes, text, caller.accountId(), Timestamp.from(now), auto,
                auto ? caller.accountId() : null);
        audit.record(caller.academyId(), caller.accountId(), "ATTENDANCE_POLICY_SET", "ACADEMY", caller.academyId(), Map.of(
                "minConnectedPercent", String.valueOf(minConnectedPercent), "lateAfterMinutes", String.valueOf(lateAfterMinutes),
                "previous", before.map(p -> p.minConnectedPercent() + "% / " + p.lateAfterMinutes() + " min").orElse("none"),
                "autoFinalise", String.valueOf(auto)));
        return find(caller.academyId()).orElseThrow();
    }
}
