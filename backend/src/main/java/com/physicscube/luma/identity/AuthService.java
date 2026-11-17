package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.PasswordPolicy;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.time.DbTime;
import java.time.Instant;
import java.util.List;
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
 * runs outside any database transaction.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final AccessTokenIssuer issuer;
    private final RefreshSessions sessions;
    private final MfaService mfa;
    private final AuthProperties properties;
    private final TransactionTemplate transaction;
    /** Verified when there is nothing real to verify, so response time does not reveal whether an account exists. */
    private final String decoyHash;
    private final AccountService accountService;
    private final com.physicscube.luma.audit.AuditLog audit;

    AuthService(AccountRepository accounts, PasswordHasher hasher, AccessTokenIssuer issuer,
                RefreshSessions sessions, MfaService mfa, AuthProperties properties,
                PlatformTransactionManager transactionManager, AccountService accountService,
                com.physicscube.luma.audit.AuditLog audit) {
        this.accountService = accountService;
        this.audit = audit;
        this.sessions = sessions;
        this.mfa = mfa;
        this.accounts = accounts;
        this.hasher = hasher;
        this.issuer = issuer;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.decoyHash = hasher.hash(UUID.randomUUID().toString());
    }

    /** Password step done but a second factor is still needed; no tokens have been issued. */
    public record MfaChallenge(String mfaToken, long expiresInSeconds, boolean enrolled) {
        @Override
        public String toString() {
            return "MfaChallenge[<redacted>]";
        }
    }

    /** Exactly one of the two is set. */
    public record LoginOutcome(SessionTokens tokens, MfaChallenge challenge) {
    }

    public record MfaEnrollment(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "MfaEnrollment[<redacted>]";
        }
    }

    /** @param recoveryCodes shown once, only when this verification completed enrolment; otherwise {@code null} */
    public record MfaOutcome(SessionTokens tokens, List<String> recoveryCodes) {
        @Override
        public String toString() {
            return "MfaOutcome[<redacted>]";
        }
    }

    /**
     * Every staff role needs a second factor: owners and administrators manage the academy, and
     * instructors, attendance officers and analysts can see students' data. Students do not.
     */
    static boolean isElevated(Set<Role> roles) {
        return roles.stream().anyMatch(role -> role != Role.STUDENT);
    }

    /**
     * Owner and administrator accounts, and any account with an authenticator, get a
     * challenge instead of tokens and must finish with {@link #completeMfa}.
     */
    public LoginOutcome login(String email, String password) {
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
            // This update holds the account lock through challenge or session creation.
            if (accounts.recordLoginSuccess(account.getId(), account.getAuthVersion(), DbTime.now(), attemptLockUntil) != 1) {
                throw new InvalidCredentialsException();
            }
            AccountSummary summary = accounts.findById(account.getId()).map(AccountSummary::of)
                    .orElseThrow(InvalidCredentialsException::new);
            if (isElevated(summary.roles()) || mfa.isEnrolled(summary.id())) {
                MfaService.Challenge challenge = mfa.begin(summary.id(), summary.authVersion());
                return new LoginOutcome(null, new MfaChallenge(challenge.token(), challenge.expiresInSeconds(), challenge.enrolled()));
            }
            return new LoginOutcome(tokens(summary, sessions.start(summary.id(), summary.authVersion(), false)), null);
        });
    }

    /** First sign-in of an account that needs MFA: returns the secret to load into an authenticator app. */
    public MfaEnrollment beginMfaEnrollment(String mfaToken) {
        MfaService.Enrollment enrollment = mfa.enroll(mfaToken);
        if (enrollment == null) {
            throw new InvalidMfaException();
        }
        return new MfaEnrollment(enrollment.secret(), enrollment.otpauthUri());
    }

    /** Second step of sign-in: a 6-digit authenticator code or a recovery code. */
    public MfaOutcome completeMfa(String mfaToken, String code) {
        MfaOutcome outcome = transaction.execute(status -> {
            MfaService.Verified verified = mfa.verify(mfaToken, code);
            if (verified == null) {
                // Commit an incorrect code's failure count before returning 401.
                return null;
            }
            AccountSummary account = accounts.findById(verified.accountId()).map(AccountSummary::of)
                    .orElseThrow(InvalidMfaException::new);
            return new MfaOutcome(tokens(account, sessions.start(account.id(), account.authVersion(), true)),
                    verified.recoveryCodes());
        });
        if (outcome == null) {
            throw new InvalidMfaException();
        }
        return outcome;
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

    /**
     * Removes the caller's authenticator after re-checking the password, and ends all
     * of their sessions. The next sign-in enrols a new authenticator.
     */
    public void resetMfa(UUID accountId, UUID sessionId, String password) {
        Account account = accounts.findById(accountId).orElseThrow(InvalidCredentialsException::new);
        Instant now = DbTime.now();
        Instant attemptLockUntil = DbTime.truncate(now.plus(properties.lockDuration()));
        if (password == null || password.length() > PasswordPolicy.MAX_LENGTH * 2 || account.getPasswordHash() == null
                || accounts.reserveLoginAttempt(accountId, properties.maxFailedLogins(), now,
                        attemptLockUntil) != 1
                || !hasher.matches(password, account.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        transaction.executeWithoutResult(status -> {
            if (accounts.recordLoginSuccess(accountId, account.getAuthVersion(), DbTime.now(), attemptLockUntil) != 1) {
                throw new InvalidCredentialsException();
            }
            Boolean sessionMfa = sessions.mfaStateIfActive(sessionId, accountId, account.getAuthVersion());
            if (sessionMfa == null || (!sessionMfa && mfa.isEnrolled(accountId))) {
                throw new InvalidCredentialsException();
            }
            mfa.clear(accountId);
            sessions.revokeAll(accountId);
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
