package com.physicscube.luma.identity;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued invitation emails, at least once and safely with several workers.
 *
 * <p>An email is claimed with {@code FOR UPDATE SKIP LOCKED}; in that transaction a new
 * invitation is issued (replacing any earlier link) and the claim is leased so no other
 * worker takes it. The email is sent after commit, outside any transaction, then marked
 * sent, or scheduled for retry, or failed once attempts run out. If a worker dies after
 * sending, the lease ends and the email is sent again with a new link that replaces the
 * first. An account activated meanwhile is not emailed. Neither addresses nor links are
 * logged or stored here.
 */
@Service
public class InvitationEmailSender {
    private static final Logger log = LoggerFactory.getLogger(InvitationEmailSender.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final InvitationService invitations;
    private final AccountMailer mailer;
    private final MailProperties mail;
    private final BulkInvitationProperties settings;
    private final AuditLog audit;

    InvitationEmailSender(JdbcTemplate jdbc, TransactionTemplate transactions, AccountRepository accounts, InvitationService invitations,
                          AccountMailer mailer, MailProperties mail, BulkInvitationProperties settings, AuditLog audit) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accounts = accounts;
        this.invitations = invitations;
        this.mailer = mailer;
        this.mail = mail;
        this.settings = settings;
        this.audit = audit;
    }

    private record Claim(UUID id, UUID accountId, int attempt, String to, String link, Instant expiresAt) {
    }

    /** Sends up to one batch of due emails; returns how many were handled (sent, retried, failed or cancelled). */
    public int runOnce() {
        if (!mail.enabled()) {
            return 0;
        }
        int handled = 0;
        while (handled < settings.batchSize()) {
            Claim claim = transactions.execute(status -> claim());
            if (claim == null) {
                break;
            }
            handled++;
            if (claim.to() == null) {
                continue;
            }
            try {
                mailer.invitation(claim.to(), claim.link(), claim.expiresAt());
                jdbc.update("UPDATE invitation_emails SET status = 'SENT', finished_at = ?, last_error = NULL WHERE id = ? AND status = 'QUEUED'",
                        Timestamp.from(DbTime.now()), claim.id());
            } catch (RuntimeException e) {
                failed(claim, e);
            }
        }
        return handled;
    }

    private Claim claim() {
        Instant now = DbTime.now();
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, account_id, academy_id, requested_by, attempts FROM invitation_emails
                WHERE status = 'QUEUED' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, Timestamp.from(now));
        if (due.isEmpty()) {
            return null;
        }
        Map<String, Object> row = due.get(0);
        UUID id = (UUID) row.get("id");
        UUID accountId = (UUID) row.get("account_id");
        UUID requestedBy = (UUID) row.get("requested_by");
        int attempt = ((Number) row.get("attempts")).intValue() + 1;
        Account account = accounts.findWithLockById(accountId).orElse(null);
        if (account == null || account.getStatus() != AccountStatus.PENDING_ACTIVATION) {
            jdbc.update("UPDATE invitation_emails SET status = 'CANCELLED', finished_at = ?, last_error = 'No longer pending activation' WHERE id = ?",
                    Timestamp.from(now), id);
            return new Claim(id, accountId, attempt, null, null, null);
        }
        IssuedInvitation issued = invitations.issue(accountId, requestedBy);
        jdbc.update("UPDATE invitation_emails SET attempts = ?, next_attempt_at = ? WHERE id = ?",
                attempt, Timestamp.from(now.plus(settings.lease())), id);
        audit.record((UUID) row.get("academy_id"), requestedBy, "INVITATION_ISSUED", "ACCOUNT", accountId,
                Map.of("expiresAt", issued.expiresAt().toString(), "delivery", "EMAIL", "bulk", "true", "attempt", String.valueOf(attempt)));
        return new Claim(id, accountId, attempt, account.getEmail(), mail.webOrigin() + "/activate#token=" + issued.token(),
                issued.expiresAt());
    }

    private void failed(Claim claim, RuntimeException e) {
        Instant now = DbTime.now();
        String reason = e.getClass().getSimpleName();
        if (claim.attempt() >= settings.maxAttempts()) {
            jdbc.update("UPDATE invitation_emails SET status = 'FAILED', finished_at = ?, last_error = ? WHERE id = ? AND status = 'QUEUED'",
                    Timestamp.from(now), reason, claim.id());
            log.warn("Invitation email for account {} failed after {} attempts: {}", claim.accountId(), claim.attempt(), reason);
        } else {
            jdbc.update("UPDATE invitation_emails SET next_attempt_at = ?, last_error = ? WHERE id = ? AND status = 'QUEUED'",
                    Timestamp.from(now.plus(settings.backoffAfter(claim.attempt()))), reason, claim.id());
            log.info("Invitation email for account {} failed (attempt {}), will retry: {}", claim.accountId(), claim.attempt(), reason);
        }
    }
}
