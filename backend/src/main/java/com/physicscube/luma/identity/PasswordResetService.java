package com.physicscube.luma.identity;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.PasswordReset;
import com.physicscube.luma.shared.time.DbTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Self-service password reset. A request never reveals whether the address belongs to
 * an account: the caller always gets the same answer, and the email is sent after the
 * request has finished. Only active accounts can be reset; a pending account uses its
 * invitation. Completing a reset revokes every session and clears a lockout, but not
 * MFA: owners and administrators still need their authenticator.
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AccountRepository accounts;
    private final PasswordResetRepository resets;
    private final AccountService accountService;
    private final RefreshSessions sessions;
    private final AuditLog audit;
    private final PasswordResetProperties properties;
    private final PasswordResetMailer mailer;
    private final PasswordResetDelivery delivery;

    PasswordResetService(AccountRepository accounts, PasswordResetRepository resets, AccountService accountService,
                         RefreshSessions sessions, AuditLog audit, PasswordResetProperties properties,
                         PasswordResetMailer mailer, PasswordResetDelivery delivery) {
        this.accounts = accounts;
        this.resets = resets;
        this.accountService = accountService;
        this.sessions = sessions;
        this.audit = audit;
        this.properties = properties;
        this.mailer = mailer;
        this.delivery = delivery;
    }

    public boolean available() {
        return properties.enabled();
    }

    /** Emails a reset link if the address belongs to an active account and its hourly limit allows. */
    @Transactional
    public void request(String rawEmail) {
        if (!available()) {
            throw new PasswordResetUnavailableException();
        }
        String email;
        try {
            email = EmailAddress.normalize(rawEmail);
        } catch (IllegalArgumentException e) {
            return;
        }
        Optional<Account> found = accounts.findByEmail(email).flatMap(a -> accounts.findWithLockById(a.getId()));
        if (found.isEmpty() || found.get().getStatus() != AccountStatus.ACTIVE) {
            return;
        }
        Account account = found.get();
        Instant now = DbTime.now();
        if (resets.countSince(account.getId(), now.minus(Duration.ofHours(1))) >= properties.maxRequestsPerHour()) {
            log.info("Password reset request over the hourly limit for account {}", account.getId());
            return;
        }
        resets.revokeOpen(account.getId(), now);
        String token = OpaqueToken.generate();
        PasswordReset reset = resets.saveAndFlush(PasswordReset.issue(account.getId(), OpaqueToken.digest(token), now, properties.ttl()));
        audit.record(account.getAcademyId(), account.getId(), "PASSWORD_RESET_REQUESTED", "ACCOUNT", account.getId(), Map.of());
        String link = properties.webOrigin() + "/reset-password#token=" + token;
        String to = account.getEmail();
        UUID accountId = account.getId();
        Instant expiresAt = reset.getExpiresAt();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delivery.submit(() -> {
                    try {
                        mailer.send(to, link, expiresAt);
                    } catch (RuntimeException e) {
                        // Neither the address nor the link is logged.
                        log.warn("Password reset email for account {} failed: {}", accountId, e.getClass().getSimpleName());
                    }
                });
            }
        });
    }

    /** Sets the new password. A rejected password rolls the claim back, so the link can be retried. */
    @Transactional
    public void confirm(String token, String rawPassword) {
        if (!OpaqueToken.plausible(token)) {
            throw new InvalidPasswordResetException();
        }
        String tokenHash = OpaqueToken.digest(token);
        if (resets.consume(tokenHash, Instant.now()) != 1) {
            throw new InvalidPasswordResetException();
        }
        UUID accountId = resets.findByTokenHash(tokenHash).orElseThrow(InvalidPasswordResetException::new).getAccountId();
        Account account = accounts.findWithLockById(accountId).orElseThrow(InvalidPasswordResetException::new);
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidPasswordResetException();
        }
        accountService.setPassword(accountId, rawPassword);
        // The new auth version already rejects old tokens; revoking the rows ends refresh sessions now too.
        sessions.revokeAll(accountId);
        audit.record(account.getAcademyId(), accountId, "PASSWORD_RESET_COMPLETED", "ACCOUNT", accountId, Map.of());
    }
}
