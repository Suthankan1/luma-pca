package com.physicscube.luma.identity;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.EnumSet;
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
 * <p>Rules against privilege escalation and lock-out:
 * <ul>
 *   <li>Owners and administrators manage staff; only an owner may grant, change or
 *       remove the owner or administrator role, or disable or invite such an account.</li>
 *   <li>Nobody changes their own roles or disables themselves.</li>
 *   <li>An academy always keeps at least one active owner.</li>
 *   <li>Students are managed through student records, never here.</li>
 * </ul>
 * Every change locks the academy row and re-reads the caller's roles under that lock,
 * so two concurrent changes cannot each rely on authority the other just removed.
 */
@Service
public class StaffAccountService {
    public static final Set<Role> STAFF_ROLES =
            EnumSet.of(Role.ACADEMY_OWNER, Role.ACADEMY_ADMIN, Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER, Role.ANALYST);
    private static final Set<Role> ELEVATED = EnumSet.of(Role.ACADEMY_OWNER, Role.ACADEMY_ADMIN);

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final InvitationService invitations;
    private final MfaService mfa;
    private final RefreshSessions sessions;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;

    StaffAccountService(AccountRepository accounts, AccountService accountService, InvitationService invitations,
                        MfaService mfa, RefreshSessions sessions, AuditLog audit, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.invitations = invitations;
        this.mfa = mfa;
        this.sessions = sessions;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<StaffAccountView> list(CurrentAccount caller) {
        caller.requireAcademyManager();
        return accounts.withAnyRole(caller.academyId(), STAFF_ROLES).stream()
                .filter(account -> !account.getRoles().contains(Role.STUDENT)).map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public StaffAccountView get(CurrentAccount caller, UUID accountId) {
        caller.requireAcademyManager();
        return view(staff(caller, accountId));
    }

    /** Creates a staff account pending activation; issue an invitation to let the person set a password. */
    @Transactional
    public StaffAccountView create(CurrentAccount caller, String email, List<String> requestedRoles) {
        Set<Role> roles = staffRoles(requestedRoles);
        Account me = lockAndReloadCaller(caller);
        requireMayGrant(me, roles);
        String normalized;
        try {
            normalized = EmailAddress.normalize(email);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Email is not a valid address");
        }
        AccountSummary created;
        try {
            created = accountService.register(caller.academyId(), normalized, roles);
        } catch (DuplicateEmailException e) {
            throw new ConflictException("An account with this email already exists");
        }
        audit.record(caller.academyId(), me.getId(), "STAFF_ACCOUNT_CREATED", "ACCOUNT", created.id(),
                Map.of("roles", names(roles)));
        return view(accounts.findById(created.id()).orElseThrow());
    }

    /** Replaces the target's roles. Removing a role takes effect on the target's next request. */
    @Transactional
    public StaffAccountView replaceRoles(CurrentAccount caller, UUID accountId, List<String> requestedRoles,
                                         long expectedVersion) {
        Set<Role> roles = staffRoles(requestedRoles);
        Account me = lockAndReloadCaller(caller);
        Account target = staff(caller, accountId);
        if (target.getId().equals(me.getId())) {
            throw new AccessDeniedException("You cannot change your own roles");
        }
        if (target.getVersion() != expectedVersion) {
            throw new ConflictException("The record was changed by someone else; reload and retry");
        }
        requireMayManage(me, target);
        requireMayGrant(me, roles);
        Set<Role> before = target.getRoles();
        if (before.contains(Role.ACADEMY_OWNER) && !roles.contains(Role.ACADEMY_OWNER)) {
            requireAnotherActiveOwner(caller.academyId(), target);
        }
        target.replaceRoles(roles);
        Account saved = accounts.saveAndFlush(target);
        audit.record(caller.academyId(), me.getId(), "STAFF_ROLES_CHANGED", "ACCOUNT", target.getId(),
                Map.of("before", names(before), "after", names(roles)));
        return view(saved);
    }

    /** Blocks sign-in, ends every session and rejects existing tokens. There is no re-enable yet. */
    @Transactional
    public StaffAccountView disable(CurrentAccount caller, UUID accountId) {
        Account me = lockAndReloadCaller(caller);
        Account target = staff(caller, accountId);
        if (target.getId().equals(me.getId())) {
            throw new AccessDeniedException("You cannot disable your own account");
        }
        requireMayManage(me, target);
        if (target.getStatus() == AccountStatus.DISABLED) {
            return view(target);
        }
        if (target.getRoles().contains(Role.ACADEMY_OWNER)) {
            requireAnotherActiveOwner(caller.academyId(), target);
        }
        target.disable();
        Account saved = accounts.saveAndFlush(target);
        sessions.revokeAll(target.getId());
        audit.record(caller.academyId(), me.getId(), "STAFF_ACCOUNT_DISABLED", "ACCOUNT", target.getId(), Map.of());
        return view(saved);
    }

    /**
     * Issues an activation invitation for a pending account of the academy, staff or
     * student, replacing any earlier one. The token is returned once and never stored.
     */
    @Transactional
    public IssuedInvitation invite(CurrentAccount caller, UUID accountId) {
        Account me = lockAndReloadCaller(caller);
        Account target = accounts.findByIdAndAcademyId(accountId, caller.academyId())
                .orElseThrow(() -> new ResourceNotFoundException("Account"));
        requireMayManage(me, target);
        if (target.getStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new ConflictException("Only an account pending activation can be invited");
        }
        IssuedInvitation issued = invitations.issue(target.getId(), me.getId());
        audit.record(caller.academyId(), me.getId(), "INVITATION_ISSUED", "ACCOUNT", target.getId(),
                Map.of("expiresAt", issued.expiresAt().toString()));
        return issued;
    }

    /**
     * Serialises staff administration per academy and returns the caller as stored now,
     * after any change committed by a concurrent request.
     */
    private Account lockAndReloadCaller(CurrentAccount caller) {
        caller.requireAcademyManager();
        jdbc.queryForObject("SELECT id FROM academies WHERE id = ? FOR UPDATE", UUID.class, caller.academyId());
        Account me = accounts.findByIdAndAcademyId(caller.accountId(), caller.academyId())
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("Caller is no longer active"));
        if (me.getRoles().stream().noneMatch(ELEVATED::contains)) {
            throw new AccessDeniedException("Academy owner or administrator role required");
        }
        return me;
    }

    private Account staff(CurrentAccount caller, UUID accountId) {
        return accounts.findByIdAndAcademyId(accountId, caller.academyId())
                .filter(account -> !account.getRoles().contains(Role.STUDENT))
                .filter(account -> account.getRoles().stream().anyMatch(STAFF_ROLES::contains))
                .orElseThrow(() -> new ResourceNotFoundException("Staff account"));
    }

    private static void requireMayGrant(Account me, Set<Role> roles) {
        if (roles.stream().anyMatch(ELEVATED::contains) && !me.getRoles().contains(Role.ACADEMY_OWNER)) {
            throw new AccessDeniedException("Only an academy owner may grant the owner or administrator role");
        }
    }

    private static void requireMayManage(Account me, Account target) {
        if (target.getRoles().stream().anyMatch(ELEVATED::contains) && !me.getRoles().contains(Role.ACADEMY_OWNER)) {
            throw new AccessDeniedException("Only an academy owner may manage an owner or administrator account");
        }
    }

    private void requireAnotherActiveOwner(UUID academyId, Account target) {
        if (accounts.activeOwnersOtherThan(academyId, target.getId()) == 0) {
            throw new ConflictException("The academy must keep at least one active owner");
        }
    }

    private static Set<Role> staffRoles(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new InvalidRequestException("At least one role is required");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String name : requested) {
            Role role;
            try {
                role = Role.valueOf(String.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Unknown role");
            }
            if (!STAFF_ROLES.contains(role)) {
                throw new InvalidRequestException("Students are managed through student records, not staff accounts");
            }
            roles.add(role);
        }
        if (roles.size() != requested.size()) {
            throw new InvalidRequestException("roles must not repeat a role");
        }
        return roles;
    }

    private static List<String> names(Set<Role> roles) {
        return roles.stream().map(Role::name).sorted().toList();
    }

    private StaffAccountView view(Account account) {
        return new StaffAccountView(account.getId(), account.getEmail(), names(account.getRoles()), account.getStatus().name(),
                mfa.isEnrolled(account.getId()), account.getVersion(), account.getCreatedAt());
    }
}
