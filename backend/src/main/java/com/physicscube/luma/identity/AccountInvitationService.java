package com.physicscube.luma.identity;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.api.ServiceUnavailableException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activation invitations that staff issue for pending accounts of their academy (students, in practice). Staff accounts
 * themselves are added and blocked by the operator (owner decision 2026-11-20: no staff page; see
 * {@code docs/delivery/runbooks/STAFF-ACCOUNTS.md}).
 */
@Service
public class AccountInvitationService {
    private final AccountRepository accounts;
    private final InvitationService invitations;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;
    private final MailProperties mail;
    private final AccountMailer mailer;
    private final MailDelivery delivery;

    AccountInvitationService(AccountRepository accounts, InvitationService invitations, AuditLog audit, JdbcTemplate jdbc,
                             MailProperties mail, AccountMailer mailer, MailDelivery delivery) {
        this.accounts = accounts;
        this.invitations = invitations;
        this.audit = audit;
        this.jdbc = jdbc;
        this.mail = mail;
        this.mailer = mailer;
        this.delivery = delivery;
    }

    /**
     * Issues an activation invitation for a pending account of the academy, replacing any earlier one. The token is
     * returned once and never stored. With {@code email} the link is also emailed to the account's address after the
     * change commits; refused (503) when email is not configured, before anything changes.
     */
    @Transactional
    public IssuedInvitation invite(CurrentAccount caller, UUID accountId, boolean email) {
        if (email && !mail.enabled()) {
            throw new ServiceUnavailableException("Email is not set up for this academy yet. Create a link to copy instead.");
        }
        Account me = lockAndReloadCaller(caller);
        Account target = accounts.findByIdAndAcademyId(accountId, caller.academyId())
                .orElseThrow(() -> new ResourceNotFoundException("Account"));
        if (target.getStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new ConflictException("Only an account pending activation can be invited");
        }
        IssuedInvitation issued = invitations.issue(target.getId(), me.getId());
        audit.record(caller.academyId(), me.getId(), "INVITATION_ISSUED", "ACCOUNT", target.getId(),
                Map.of("expiresAt", issued.expiresAt().toString(), "delivery", email ? "EMAIL" : "LINK"));
        if (email) {
            String to = target.getEmail();
            String link = mail.webOrigin() + "/activate#token=" + issued.token();
            delivery.afterCommit(target.getId(), "Invitation", () -> mailer.invitation(to, link, issued.expiresAt()));
        }
        return issued;
    }

    /** Serialises invitations per academy and returns the caller as stored now (still active staff). */
    private Account lockAndReloadCaller(CurrentAccount caller) {
        caller.requireStaff();
        jdbc.queryForObject("SELECT id FROM academies WHERE id = ? FOR UPDATE", UUID.class, caller.academyId());
        Account me = accounts.findByIdAndAcademyId(caller.accountId(), caller.academyId())
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("Caller is no longer active"));
        if (!me.getRoles().contains(Role.STAFF)) {
            throw new AccessDeniedException("Staff role required");
        }
        return me;
    }
}
