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
 * Creates an academy's first owner, the one account no administrator can invite.
 * Reachable only from the operator command ({@link OwnerBootstrapCommand}), never over
 * HTTP. It never replaces or overrides an owner: it refuses when the academy already
 * has an active owner, and re-running it before the owner activates only issues a new
 * invitation (cancelling the old one).
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
            throw new RefusedException("Give the owner's email address.");
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

        List<Map<String, Object>> owners = jdbc.queryForList("""
                SELECT a.id, a.email, a.status FROM accounts a JOIN account_roles r ON r.account_id = a.id
                WHERE a.academy_id = ? AND r.role = 'STAFF' AND a.status <> 'DISABLED'
                """, academyId);
        if (owners.stream().anyMatch(o -> "ACTIVE".equals(o.get("status")))) {
            throw new RefusedException("This academy already has active staff. Staff invite other staff from the Team screen.");
        }
        UUID ownerId;
        boolean ownerCreated;
        if (!owners.isEmpty()) {
            Map<String, Object> pending = owners.get(0);
            if (owners.size() > 1 || !email.equals(pending.get("email"))) {
                throw new RefusedException("A staff invitation is already pending for another address. Re-run with that address "
                        + "to send a new invitation.");
            }
            ownerId = (UUID) pending.get("id");
            ownerCreated = false;
        } else {
            if (accounts.findByEmail(email).isPresent()) {
                throw new RefusedException("That address already belongs to another LUMA account. Use a different address.");
            }
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
