package com.physicscube.luma.identity;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a staff member to an academy (creating the academy the first time): a pending STAFF account and its activation
 * invitation. With one staff role and no staff page (owner decision 2026-11-20) this operator command is how staff are
 * added; reachable only from {@link OwnerBootstrapCommand}, never over HTTP. It never takes over an existing account:
 * it refuses an address that is active, disabled or belongs to a student or another academy, and running it again for a
 * pending staff member only issues a new invitation (cancelling the old one).
 */
@Service
public class OwnerBootstrapService {
    private final AccountService accounts;
    private final InvitationService invitations;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;

    OwnerBootstrapService(AccountService accounts, InvitationService invitations, AuditLog audit, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.invitations = invitations;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public record Result(UUID academyId, boolean academyCreated, UUID ownerId, boolean ownerCreated, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "Result[academyId=" + academyId + ", academyCreated=" + academyCreated + ", ownerId=" + ownerId
                    + ", ownerCreated=" + ownerCreated + ", token=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    /** Refusals carry a message written for the operator. */
    public static class RefusedException extends RuntimeException {
        public RefusedException(String message) {
            super(message);
        }
    }

    @Transactional
    public Result bootstrap(String rawAcademyName, String rawOwnerEmail) {
        String name = rawAcademyName == null ? "" : rawAcademyName.strip();
        if (name.isEmpty() || name.length() > 200) {
            throw new RefusedException("Give the academy name, up to 200 characters.");
        }
        String email;
        try {
            email = EmailAddress.normalize(rawOwnerEmail);
        } catch (IllegalArgumentException e) {
            throw new RefusedException("Give the staff member's email address.");
        }
        // One bootstrap at a time, across every instance and operator.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('luma-owner-bootstrap'))", Object.class);

        List<UUID> named = jdbc.queryForList("SELECT id FROM academies WHERE name = ?", UUID.class, name);
        if (named.size() > 1) {
            throw new RefusedException("More than one academy is called \"" + name + "\"; resolve that in the database first.");
        }
        boolean academyCreated = named.isEmpty();
        UUID academyId = academyCreated ? UUID.randomUUID() : named.get(0);
        if (academyCreated) {
            jdbc.update("INSERT INTO academies (id, name) VALUES (?, ?)", academyId, name);
        }

        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT a.id, a.academy_id, a.status,
                       EXISTS (SELECT 1 FROM account_roles r WHERE r.account_id = a.id AND r.role = 'STAFF') AS staff
                FROM accounts a WHERE a.email = ?
                """, email);
        UUID ownerId;
        boolean ownerCreated;
        if (!existing.isEmpty()) {
            Map<String, Object> account = existing.get(0);
            boolean ours = academyId.equals(account.get("academy_id")) && Boolean.TRUE.equals(account.get("staff"));
            if (!ours) {
                throw new RefusedException("That address already belongs to another LUMA account. Use a different address.");
            }
            if ("ACTIVE".equals(account.get("status"))) {
                throw new RefusedException("This staff member is already active. They sign in with their password, or reset it by email.");
            }
            if (!"PENDING_ACTIVATION".equals(account.get("status"))) {
                throw new RefusedException("This staff account is blocked. Unblock it with the SQL in docs/delivery/runbooks/STAFF-ACCOUNTS.md.");
            }
            ownerId = (UUID) account.get("id");
            ownerCreated = false;
        } else {
            ownerId = accounts.register(academyId, email, Set.of(Role.STAFF)).id();
            ownerCreated = true;
        }
        IssuedInvitation issued = invitations.issue(ownerId, null);
        // Done by the operator, not by an account: no actor.
        audit.record(academyId, null, "OWNER_BOOTSTRAPPED", "ACCOUNT", ownerId, Map.of(
                "academyCreated", Boolean.toString(academyCreated),
                "ownerCreated", Boolean.toString(ownerCreated),
                "expiresAt", issued.expiresAt().toString()));
        return new Result(academyId, academyCreated, ownerId, ownerCreated, issued.token(), issued.expiresAt());
    }
}
