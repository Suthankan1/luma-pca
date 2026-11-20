package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.PasswordPolicy;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.time.DbTime;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password sign-in, token refresh and logout. Each sign-in attempt is counted against the account before the password
 * is checked, so parallel guesses cannot exceed the lockout limit. Password hashing
 * runs outside any database transaction. There is no second factor: a correct password opens a session for every role.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final AccessTokenIssuer issuer;
    private final RefreshSessions sessions;
    private final AuthProperties properties;
    private final TransactionTemplate transaction;
    /** Verified when there is nothing real to verify, so response time does not reveal whether an account exists. */
    private final String decoyHash;
    private final AccountService accountService;
    private final com.physicscube.luma.audit.AuditLog audit;

    AuthService(AccountRepository accounts, PasswordHasher hasher, AccessTokenIssuer issuer,
                RefreshSessions sessions, AuthProperties properties,
                PlatformTransactionManager transactionManager, AccountService accountService,
                com.physicscube.luma.audit.AuditLog audit) {
        this.accountService = accountService;
        this.audit = audit;
        this.sessions = sessions;
        this.accounts = accounts;
        this.hasher = hasher;
        this.issuer = issuer;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.decoyHash = hasher.hash(UUID.randomUUID().toString());
    }

    /** Email and password sign-in for any active account, staff or student; returns the session's tokens. */
    public SessionTokens login(String email, String password) {
        if (password == null || password.length() > PasswordPolicy.MAX_LENGTH * 2) {
            throw new InvalidCredentialsException();
        }
        Optional<Account> found = lookup(email)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE && account.getPasswordHash() != null);
        Instant now = DbTime.now();
        Instant attemptLockUntil = DbTime.truncate(now.plus(properties.lockDuration()));
        boolean attemptAllowed = found.isPresent() && accounts.reserveLoginAttempt(
                found.get().getId(), properties.maxFailedLogins(), now, attemptLockUntil) == 1;
        if (!attemptAllowed) {
            hasher.matches(password, decoyHash);
            throw new InvalidCredentialsException();
        }
        Account account = found.get();
        if (!hasher.matches(password, account.getPasswordHash())) {
            if (accounts.isLocked(account.getId(), Instant.now())) {
                log.warn("Account {} locked after repeated failed sign-in attempts", account.getId());
            }
            throw new InvalidCredentialsException();
        }
        return transaction.execute(status -> {
            // This update holds the account lock through session creation.
            if (accounts.recordLoginSuccess(account.getId(), account.getAuthVersion(), DbTime.now(), attemptLockUntil) != 1) {
                throw new InvalidCredentialsException();
            }
            AccountSummary summary = accounts.findById(account.getId()).map(AccountSummary::of)
                    .orElseThrow(InvalidCredentialsException::new);
            return tokens(summary, sessions.start(summary.id(), summary.authVersion()));
        });
    }

    /**
     * The Academy-requested student sign-in (student ID as both username and password). The caller has already
     * checked the typed password equals the student ID and found the student's account; this only ever opens a
     * session for an account whose sole role is STUDENT, so it can never grant staff access. A pending account
     * (never activated) is activated with a random password nobody knows, so email sign-in stays closed to it.
     * Disabled and locked accounts are refused exactly like a wrong password.
     */
    public SessionTokens studentIdLogin(UUID accountId) {
        AccountSummary found = accountService.findById(accountId)
                .filter(account -> account.status() != AccountStatus.DISABLED)
                .filter(account -> account.roles().equals(Set.of(Role.STUDENT)))
                .orElseThrow(InvalidCredentialsException::new);
        if (found.status() == AccountStatus.PENDING_ACTIVATION) {
            accountService.setPassword(accountId, UUID.randomUUID() + "-" + UUID.randomUUID());
        }
        int authVersion = accountService.findById(accountId).orElseThrow(InvalidCredentialsException::new).authVersion();
        Instant now = DbTime.now();
        Instant attemptLockUntil = DbTime.truncate(now.plus(properties.lockDuration()));
        if (accounts.reserveLoginAttempt(accountId, properties.maxFailedLogins(), now, attemptLockUntil) != 1) {
            throw new InvalidCredentialsException();
        }
        return transaction.execute(status -> {
            if (accounts.recordLoginSuccess(accountId, authVersion, DbTime.now(), attemptLockUntil) != 1) {
                throw new InvalidCredentialsException();
            }
            AccountSummary summary = accounts.findById(accountId).map(AccountSummary::of)
                    .orElseThrow(InvalidCredentialsException::new);
            if (!summary.roles().equals(Set.of(Role.STUDENT))) {
                throw new InvalidCredentialsException();
            }
            return tokens(summary, sessions.start(summary.id(), summary.authVersion()));
        });
    }

    /**
     * Changes the signed-in person's password after re-checking the current one (a failed check
     * counts towards the lockout like a failed sign-in), then ends every session on every device.
     *
     * @throws com.physicscube.luma.shared.api.InvalidRequestException if the current password is wrong
     */
    public void changePassword(UUID accountId, String currentPassword, String newPassword) {
        Account account = accounts.findById(accountId).orElseThrow(InvalidCredentialsException::new);
        Instant now = DbTime.now();
        Instant attemptLockUntil = DbTime.truncate(now.plus(properties.lockDuration()));
        if (currentPassword == null || currentPassword.length() > PasswordPolicy.MAX_LENGTH * 2 || account.getPasswordHash() == null
                || accounts.reserveLoginAttempt(accountId, properties.maxFailedLogins(), now, attemptLockUntil) != 1
                || !hasher.matches(currentPassword, account.getPasswordHash())) {
            throw new com.physicscube.luma.shared.api.InvalidRequestException("The current password is not correct");
        }
        transaction.executeWithoutResult(status -> {
            accounts.recordLoginSuccess(accountId, account.getAuthVersion(), DbTime.now(), attemptLockUntil);
            accountService.setPassword(accountId, newPassword);
            sessions.revokeAll(accountId);
            audit.record(account.getAcademyId(), accountId, "PASSWORD_CHANGED", "ACCOUNT", accountId, java.util.Map.of());
        });
    }

    /** Exchanges a refresh token for a new pair. The presented token can never be used again. */
    public SessionTokens refresh(String refreshToken) {
        RefreshSessions.Issued issued = sessions.rotate(refreshToken);
        if (issued == null) {
            throw new InvalidSessionException();
        }
        AccountSummary account = accounts.findById(issued.accountId()).map(AccountSummary::of)
                .orElseThrow(InvalidSessionException::new);
        return tokens(account, issued);
    }

    /** Idempotent: unknown or already ended sessions are ignored. */
    public void logout(String refreshToken) {
        sessions.revokeByToken(refreshToken);
    }

    public void logoutAll(UUID accountId) {
        sessions.revokeAll(accountId);
    }

    private SessionTokens tokens(AccountSummary account, RefreshSessions.Issued issued) {
        AccessToken access = issuer.issue(account, issued.sessionId());
        return new SessionTokens(access.value(), access.expiresInSeconds(), issued.refreshToken(), issued.refreshExpiresInSeconds());
    }

    private Optional<Account> lookup(String email) {
        try {
            return accounts.findByEmail(EmailAddress.normalize(email));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
