package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.Invitation;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems activation invitations. It does not deliver the token: the
 * caller is responsible for sending it. Only {@link #accept} is reachable over HTTP.
 */
@Service
public class InvitationService {
    public static final Duration DEFAULT_TTL = Duration.ofHours(72);
    public static final Duration MAX_TTL = Duration.ofDays(14);

    private final AccountRepository accounts;
    private final InvitationRepository invitations;
    private final AccountService accountService;

    InvitationService(AccountRepository accounts, InvitationRepository invitations, AccountService accountService) {
        this.accounts = accounts;
        this.invitations = invitations;
        this.accountService = accountService;
    }

    @Transactional
    public IssuedInvitation issue(UUID accountId, UUID createdBy) {
        return issue(accountId, createdBy, DEFAULT_TTL);
    }

    /** Replaces any earlier unused invitation for the account. */
    @Transactional
    public IssuedInvitation issue(UUID accountId, UUID createdBy, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Invitation lifetime must be positive and at most " + MAX_TTL.toDays() + " days");
        }
        // The row lock serialises concurrent issues for one account.
        Account account = accounts.findWithLockById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        if (account.getStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("Only an account pending activation can be invited");
        }
        Instant now = Instant.now();
        invitations.revokeOpen(accountId, now);
        String token = OpaqueToken.generate();
        Invitation invitation = invitations.saveAndFlush(Invitation.issue(accountId, OpaqueToken.digest(token), createdBy, now, ttl));
        return new IssuedInvitation(accountId, token, invitation.getExpiresAt());
    }

    @Transactional
    public void revoke(UUID accountId) {
        invitations.revokeOpen(accountId, Instant.now());
    }

    /**
     * Activates the invited account with the chosen password. A rejected password
     * rolls the claim back, so the invitation can be retried.
     */
    @Transactional
    public AccountSummary accept(String token, String rawPassword) {
        if (!OpaqueToken.plausible(token)) {
            throw new InvalidInvitationException();
        }
        String tokenHash = OpaqueToken.digest(token);
        if (invitations.consume(tokenHash, Instant.now()) != 1) {
            throw new InvalidInvitationException();
        }
        UUID accountId = invitations.findByTokenHash(tokenHash).orElseThrow(InvalidInvitationException::new).getAccountId();
        Account account = accounts.findById(accountId).orElseThrow(InvalidInvitationException::new);
        if (account.getStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new InvalidInvitationException();
        }
        accountService.setPassword(accountId, rawPassword);
        return AccountSummary.of(account);
    }
}
