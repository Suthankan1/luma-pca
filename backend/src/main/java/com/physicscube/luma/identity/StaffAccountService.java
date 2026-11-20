package com.physicscube.luma.identity;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.api.ServiceUnavailableException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff accounts of the caller's academy and activation invitations.
 *
 * <p>There is one staff role (owner decision 2026-11-20): every active staff member manages
 * staff. Rules against lock-out:
 * <ul>
 *   <li>Nobody disables themselves.</li>
 *   <li>An academy always keeps at least one active staff account.</li>
 *   <li>Students are managed through student records, never here.</li>
 * </ul>
 * Every change locks the academy row and re-reads the caller under that lock, so two
 * concurrent changes (two staff disabling each other) cannot both succeed.
 */
@Service
public class StaffAccountService {
    private static final Set<Role> STAFF_ONLY = Set.of(Role.STAFF);

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final InvitationService invitations;
    private final RefreshSessions sessions;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;
    private final MailProperties mail;
    private final AccountMailer mailer;
    private final MailDelivery delivery;

    StaffAccountService(AccountRepository accounts, AccountService accountService, InvitationService invitations,
                        RefreshSessions sessions, AuditLog audit, JdbcTemplate jdbc,
                        MailProperties mail, AccountMailer mailer, MailDelivery delivery) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.invitations = invitations;
        this.sessions = sessions;
        this.audit = audit;
        this.jdbc = jdbc;
        this.mail = mail;
        this.mailer = mailer;
        this.delivery = delivery;
    }

    @Transactional(readOnly = true)
    public List<StaffAccountView> list(CurrentAccount caller) {
        caller.requireStaff();
        return accounts.withAnyRole(caller.academyId(), STAFF_ONLY).stream()
                .filter(account -> !account.getRoles().contains(Role.STUDENT)).map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public StaffAccountView get(CurrentAccount caller, UUID accountId) {
        caller.requireStaff();
        return view(staff(caller, accountId));
    }

    /** Creates a staff account pending activation; issue an invitation to let the person set a password. */
    @Transactional
    public StaffAccountView create(CurrentAccount caller, String email) {
        Account me = lockAndReloadCaller(caller);
        String normalized;
        try {
            normalized = EmailAddress.normalize(email);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Email is not a valid address");
        }
        AccountSummary created;
        try {
            created = accountService.register(caller.academyId(), normalized, STAFF_ONLY);
        } catch (DuplicateEmailException e) {
            throw new ConflictException("An account with this email already exists");
        }
        audit.record(caller.academyId(), me.getId(), "STAFF_ACCOUNT_CREATED", "ACCOUNT", created.id(),
                Map.of("roles", names(STAFF_ONLY)));
        return view(accounts.findById(created.id()).orElseThrow());
    }

    /** Blocks sign-in, ends every session and rejects existing tokens. {@link #enable} undoes it. */
    @Transactional
    public StaffAccountView disable(CurrentAccount caller, UUID accountId) {
        Account me = lockAndReloadCaller(caller);
        Account target = staff(caller, accountId);
        if (target.getId().equals(me.getId())) {
            throw new AccessDeniedException("You cannot disable your own account");
        }
        if (target.getStatus() == AccountStatus.DISABLED) {
            return view(target);
        }
        requireAnotherActiveStaff(caller.academyId(), target);
        target.disable();
        Account saved = accounts.saveAndFlush(target);
        sessions.revokeAll(target.getId());
        audit.record(caller.academyId(), me.getId(), "STAFF_ACCOUNT_DISABLED", "ACCOUNT", target.getId(), Map.of());
        return view(saved);
    }

    /**
     * Lets a disabled staff member sign in again. With {@code requireNewPassword} the
     * account returns to pending activation, its old password removed and any open invitation
     * revoked, so they set a new password from a fresh invitation. Sessions ended by the
     * disable stay ended. Enabling an account that is not disabled changes nothing.
     */
    @Transactional
    public StaffAccountView enable(CurrentAccount caller, UUID accountId, boolean requireNewPassword) {
        Account me = lockAndReloadCaller(caller);
        Account target = staff(caller, accountId);
        if (target.getStatus() != AccountStatus.DISABLED) {
            return view(target);
        }
        target.enable(requireNewPassword);
        Account saved = accounts.saveAndFlush(target);
        invitations.revoke(target.getId());
        audit.record(caller.academyId(), me.getId(), "STAFF_ACCOUNT_ENABLED", "ACCOUNT", target.getId(),
                Map.of("newPasswordRequired", String.valueOf(saved.getStatus() == AccountStatus.PENDING_ACTIVATION),
                        "statusNow", saved.getStatus().name()));
        return view(saved);
    }

    /**
     * Issues an activation invitation for a pending account of the academy, staff or
     * student, replacing any earlier one. The token is returned once and never stored.
     */
    @Transactional
    public IssuedInvitation invite(CurrentAccount caller, UUID accountId) {
        return invite(caller, accountId, false);
    }

    /**
     * As {@link #invite(CurrentAccount, UUID)}; with {@code email} the link is also emailed
     * to the account's address after the change commits. Refused (503) when email is not
     * configured, before anything changes.
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

    /**
     * Serialises staff administration per academy and returns the caller as stored now,
     * after any change committed by a concurrent request.
     */
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

    private Account staff(CurrentAccount caller, UUID accountId) {
        return accounts.findByIdAndAcademyId(accountId, caller.academyId())
                .filter(account -> !account.getRoles().contains(Role.STUDENT))
                .filter(account -> account.getRoles().contains(Role.STAFF))
                .orElseThrow(() -> new ResourceNotFoundException("Staff account"));
    }

    private void requireAnotherActiveStaff(UUID academyId, Account target) {
        if (accounts.activeStaffOtherThan(academyId, target.getId()) == 0) {
            throw new ConflictException("The academy must keep at least one active staff account");
        }
    }

    private static List<String> names(Set<Role> roles) {
        return roles.stream().map(Role::name).sorted().toList();
    }

    private StaffAccountView view(Account account) {
        return new StaffAccountView(account.getId(), account.getEmail(), names(account.getRoles()), account.getStatus().name(),
                account.getVersion(), account.getCreatedAt(), account.getPasswordHash() != null);
    }
}
