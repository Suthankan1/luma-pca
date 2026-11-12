package com.physicscube.luma.identity;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.shared.api.ServiceUnavailableException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues invitation emails for many student accounts at once; {@link InvitationEmailSender}
 * sends them in the background. Only student accounts of the caller's academy that are
 * still pending activation are queued. Unless {@code resend} is set, accounts that already
 * hold a usable invitation are skipped (except when their last queued email failed), so
 * running it again reaches only those not yet invited.
 */
@Service
@EnableConfigurationProperties(BulkInvitationProperties.class)
public class InvitationEmailQueue {
    private final JdbcTemplate jdbc;
    private final MailProperties mail;
    private final AuditLog audit;

    InvitationEmailQueue(JdbcTemplate jdbc, MailProperties mail, AuditLog audit) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.audit = audit;
    }

    /**
     * @param pending student accounts among those asked about that are still waiting to activate
     * @param queued emails queued by this request (0 for a dry run: those that would be)
     */
    public record QueueResult(int pending, int queued, int alreadyQueued, int alreadyInvited) {
    }

    /** Counts for the academy: emails waiting now, and those sent, failed and cancelled in the last 14 days. */
    public record Progress(int waiting, int sent, int failed, int cancelled) {
    }

    /** @param targetType and {@code targetId} name what the request covered, for the audit trail */
    @Transactional
    public QueueResult queue(CurrentAccount caller, Collection<UUID> accountIds, boolean resend, boolean dryRun,
                             String targetType, UUID targetId) {
        caller.requireAcademyManager();
        if (!mail.enabled()) {
            throw new ServiceUnavailableException("Email is not set up for this academy yet. Create invitation links one by one instead.");
        }
        Instant now = DbTime.now();
        List<UUID> chosen = new ArrayList<>();
        int[] counts = new int[3];
        if (!accountIds.isEmpty()) {
            jdbc.query(connection -> {
                var statement = connection.prepareStatement("""
                        SELECT a.id,
                               EXISTS (SELECT 1 FROM invitation_emails q WHERE q.account_id = a.id AND q.status = 'QUEUED') AS queued,
                               EXISTS (SELECT 1 FROM account_invitations i WHERE i.account_id = a.id AND i.consumed_at IS NULL
                                         AND i.revoked_at IS NULL AND i.expires_at > ?)
                                 AND COALESCE((SELECT q.status <> 'FAILED' FROM invitation_emails q WHERE q.account_id = a.id
                                               ORDER BY q.created_at DESC LIMIT 1), true) AS invited
                        FROM accounts a
                        WHERE a.academy_id = ? AND a.id = ANY (?) AND a.status = 'PENDING_ACTIVATION'
                          AND EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = a.id AND r.role = 'STUDENT')
                          AND NOT EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = a.id AND r.role <> 'STUDENT')
                        ORDER BY a.email
                        """);
                Array ids = connection.createArrayOf("uuid", accountIds.toArray());
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setObject(2, caller.academyId());
                statement.setArray(3, ids);
                return statement;
            }, row -> {
                counts[0]++;
                if (row.getBoolean("queued")) {
                    counts[1]++;
                } else if (!resend && row.getBoolean("invited")) {
                    counts[2]++;
                } else {
                    chosen.add(row.getObject("id", UUID.class));
                }
            });
        }
        if (dryRun || chosen.isEmpty()) {
            return new QueueResult(counts[0], dryRun ? chosen.size() : 0, counts[1], counts[2]);
        }
        Timestamp at = Timestamp.from(now);
        int[][] inserted = jdbc.batchUpdate("""
                INSERT INTO invitation_emails (id, academy_id, account_id, requested_by, status, attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, 'QUEUED', 0, ?, ?)
                ON CONFLICT (account_id) WHERE status = 'QUEUED' DO NOTHING
                """, chosen, 200, (statement, accountId) -> {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, caller.academyId());
            statement.setObject(3, accountId);
            statement.setObject(4, caller.accountId());
            statement.setTimestamp(5, at);
            statement.setTimestamp(6, at);
        });
        int queued = 0;
        for (int[] chunk : inserted) {
            for (int n : chunk) {
                queued += Math.max(n, 0);
            }
        }
        // A concurrent request may have queued some of them first; those count as already queued.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("queued", String.valueOf(queued));
        details.put("resend", String.valueOf(resend));
        audit.record(caller.academyId(), caller.accountId(), "INVITATIONS_QUEUED", targetType, targetId, details);
        return new QueueResult(counts[0], queued, counts[1] + chosen.size() - queued, counts[2]);
    }

    @Transactional(readOnly = true)
    public Progress progress(CurrentAccount caller) {
        caller.requireAcademyManager();
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'QUEUED') AS waiting,
                       count(*) FILTER (WHERE status = 'SENT' AND created_at > ?) AS sent,
                       count(*) FILTER (WHERE status = 'FAILED' AND created_at > ?) AS failed,
                       count(*) FILTER (WHERE status = 'CANCELLED' AND created_at > ?) AS cancelled
                FROM invitation_emails WHERE academy_id = ?
                """, (row, n) -> new Progress(row.getInt("waiting"), row.getInt("sent"), row.getInt("failed"), row.getInt("cancelled")),
                since(), since(), since(), caller.academyId());
    }

    private static Timestamp since() {
        return Timestamp.from(DbTime.now().minus(java.time.Duration.ofDays(14)));
    }
}
