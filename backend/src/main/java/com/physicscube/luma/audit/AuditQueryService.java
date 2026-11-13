package com.physicscube.luma.audit;

import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only view of the academy's audit trail for owners and administrators, newest first,
 * paged by (time, id). A report query: it reads account emails, batch codes and class titles
 * to label events, and changes nothing.
 */
@Service
public class AuditQueryService {
    public static final int MAX_PAGE_SIZE = 200;
    private static final Pattern CURSOR = Pattern.compile("^(\\d{1,19})_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    /** Areas an administrator filters by; each is a set of action-name patterns. */
    public static final Map<String, List<String>> CATEGORIES = Map.of(
            "staff", List.of("STAFF\\_%", "OWNER\\_%"),
            "sign-in", List.of("INVITATION\\_%", "INVITATIONS\\_%", "PASSWORD\\_RESET\\_%"),
            "students", List.of("STUDENTS\\_%"),
            "classes", List.of("CLASS\\_%"),
            "zoom", List.of("ZOOM\\_%"));

    private final JdbcTemplate jdbc;

    AuditQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AuditEventPage list(CurrentAccount caller, String category, UUID targetId, String before, int limit) {
        caller.requireAcademyManager();
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.occurred_at, e.action, e.actor_account_id, actor.email AS actor_email, e.target_type, e.target_id,
                       CASE e.target_type
                         WHEN 'ACCOUNT' THEN target_account.email
                         WHEN 'BATCH' THEN target_batch.code
                         WHEN 'CLASS_SESSION' THEN target_class.title
                         WHEN 'ZOOM_OCCURRENCE' THEN target_class.title
                         WHEN 'ZOOM_IDENTITY' THEN target_student.student_number
                       END AS target_label,
                       e.details::text AS details
                FROM audit_events e
                LEFT JOIN accounts actor ON actor.id = e.actor_account_id AND actor.academy_id = e.academy_id
                LEFT JOIN accounts target_account ON e.target_type = 'ACCOUNT' AND target_account.id = e.target_id
                  AND target_account.academy_id = e.academy_id
                LEFT JOIN batches target_batch ON e.target_type = 'BATCH' AND target_batch.id = e.target_id
                  AND target_batch.academy_id = e.academy_id
                LEFT JOIN class_sessions target_class ON e.target_type IN ('CLASS_SESSION', 'ZOOM_OCCURRENCE')
                  AND target_class.id = e.target_id AND target_class.academy_id = e.academy_id
                LEFT JOIN students target_student ON e.target_type = 'ZOOM_IDENTITY' AND target_student.id = e.target_id
                  AND target_student.academy_id = e.academy_id
                WHERE e.academy_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(caller.academyId()));
        if (category != null && !category.isBlank()) {
            List<String> patterns = CATEGORIES.get(category);
            if (patterns == null) {
                throw new InvalidRequestException("category must be one of " + String.join(", ", CATEGORIES.keySet().stream().sorted().toList()));
            }
            sql.append(" AND (").append(String.join(" OR ", patterns.stream().map(p -> "e.action LIKE ?").toList())).append(')');
            args.addAll(patterns);
        }
        if (targetId != null) {
            sql.append(" AND e.target_id = ?");
            args.add(targetId);
        }
        if (before != null && !before.isBlank()) {
            Matcher cursor = CURSOR.matcher(before);
            if (!cursor.matches()) {
                throw new InvalidRequestException("before must be the nextBefore value of a previous page");
            }
            long micros = Long.parseLong(cursor.group(1));
            Instant at = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
            sql.append(" AND (e.occurred_at, e.id) < (?, ?)");
            args.add(Timestamp.from(at));
            args.add(UUID.fromString(cursor.group(2)));
        }
        sql.append(" ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?");
        args.add(limit + 1);
        List<AuditEventView> found = jdbc.query(sql.toString(), (row, i) -> new AuditEventView(
                row.getObject("id", UUID.class), row.getTimestamp("occurred_at").toInstant(), row.getString("action"),
                row.getObject("actor_account_id", UUID.class), row.getString("actor_email"), row.getString("target_type"),
                row.getObject("target_id", UUID.class), row.getString("target_label"),
                JsonParserFactory.getJsonParser().parseMap(row.getString("details"))), args.toArray());
        boolean more = found.size() > limit;
        List<AuditEventView> page = more ? found.subList(0, limit) : found;
        String next = null;
        if (more) {
            AuditEventView last = page.get(page.size() - 1);
            next = ChronoUnit.MICROS.between(Instant.EPOCH, last.occurredAt()) + "_" + last.id();
        }
        return new AuditEventPage(List.copyOf(page), next);
    }
}
