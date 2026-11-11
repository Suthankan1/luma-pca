package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.PasswordPolicy;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.Role;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Password sign-in. Each attempt is counted against the account before the password
 * is checked, so parallel guesses cannot exceed the lockout limit. Password hashing
 * runs outside any database transaction.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final AccessTokenIssuer issuer;
    private final AuthProperties properties;
    /** Verified when there is nothing real to verify, so response time does not reveal whether an account exists. */
    private final String decoyHash;

    AuthService(AccountRepository accounts, PasswordHasher hasher, AccessTokenIssuer issuer, AuthProperties properties) {
        this.accounts = accounts;
        this.hasher = hasher;
        this.issuer = issuer;
        this.properties = properties;
        this.decoyHash = hasher.hash(UUID.randomUUID().toString());
    }

    public AccessToken login(String email, String password) {
        if (password == null || password.length() > PasswordPolicy.MAX_LENGTH * 2) {
            throw new InvalidCredentialsException();
        }
        Optional<Account> found = lookup(email)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE && account.getPasswordHash() != null);
        Instant now = Instant.now();
        boolean attemptAllowed = found.isPresent() && accounts.reserveLoginAttempt(
                found.get().getId(), properties.maxFailedLogins(), now, now.plus(properties.lockDuration())) == 1;
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
        boolean elevated = account.getRoles().contains(Role.ACADEMY_OWNER) || account.getRoles().contains(Role.ACADEMY_ADMIN);
        if (elevated && !properties.allowElevatedLoginWithoutMfa()) {
            throw new MfaRequiredException();
        }
        // Fails if the account was disabled or its password changed while this attempt was in flight.
        if (accounts.recordLoginSuccess(account.getId(), account.getAuthVersion()) != 1) {
            throw new InvalidCredentialsException();
        }
        return issuer.issue(AccountSummary.of(account));
    }

    private Optional<Account> lookup(String email) {
        try {
            return accounts.findByEmail(EmailAddress.normalize(email));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
