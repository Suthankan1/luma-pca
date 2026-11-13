package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.PasswordPolicy;
import com.physicscube.luma.identity.domain.Role;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account persistence and password storage. Sign-in is in {@link AuthService} and
 * activation in {@link InvitationService}.
 */
@Service
public class AccountService {
    private final AccountRepository accounts;
    private final PasswordHasher passwordHasher;

    AccountService(AccountRepository accounts, PasswordHasher passwordHasher) {
        this.accounts = accounts;
        this.passwordHasher = passwordHasher;
    }

    /** Creates an account that cannot sign in until a password is set. */
    @Transactional
    public AccountSummary register(UUID academyId, String email, Set<Role> roles) {
        String normalized = EmailAddress.normalize(email);
        if (accounts.existsByEmail(normalized)) {
            throw new DuplicateEmailException();
        }
        try {
            return AccountSummary.of(accounts.saveAndFlush(Account.pending(academyId, normalized, roles)));
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration won the race; the unique constraint is the authority.
            if (String.valueOf(e.getMessage()).contains("uq_accounts_email")) {
                throw new DuplicateEmailException();
            }
            throw e;
        }
    }

    /** Of the normalised emails, those already used by an account (in any academy). */
    @Transactional(readOnly = true)
    public Set<String> existingEmails(Collection<String> normalizedEmails) {
        return normalizedEmails.isEmpty() ? Set.of() : Set.copyOf(accounts.existingEmails(normalizedEmails));
    }

    /**
     * Registers several pending accounts in one batch, in the given order. The caller has
     * checked the emails are new and distinct; a clash found by the unique constraint
     * (a concurrent registration) is reported as a duplicate and rolls everything back.
     */
    @Transactional
    public List<AccountSummary> registerAll(UUID academyId, List<String> emails, Set<Role> roles) {
        List<Account> created = emails.stream().map(email -> Account.pending(academyId, EmailAddress.normalize(email), roles)).toList();
        try {
            return accounts.saveAllAndFlush(created).stream().map(AccountSummary::of).toList();
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains("uq_accounts_email")) {
                throw new DuplicateEmailException();
            }
            throw e;
        }
    }

    @Transactional
    public void setPassword(UUID accountId, String rawPassword) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        PasswordPolicy.validate(rawPassword, account.getEmail());
        account.applyPasswordHash(passwordHasher.hash(rawPassword), Instant.now());
    }

    @Transactional
    public void disable(UUID accountId) {
        accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId)).disable();
    }

    @Transactional(readOnly = true)
    public Optional<AccountSummary> findById(UUID accountId) {
        return accounts.findById(accountId).map(AccountSummary::of);
    }

    /** Accounts that exist among the ids; missing ids are simply absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, AccountSummary> findByIds(Collection<UUID> accountIds) {
        return accounts.findAllById(accountIds).stream().map(AccountSummary::of)
                .collect(Collectors.toMap(AccountSummary::id, Function.identity()));
    }

    /** For other modules: the academy's accounts with these normalised emails, by email. */
    @Transactional(readOnly = true)
    public Map<String, AccountSummary> findInAcademyByEmails(UUID academyId, Collection<String> normalizedEmails) {
        return normalizedEmails.isEmpty() ? Map.of() : accounts.findByAcademyIdAndEmailIn(academyId, normalizedEmails).stream()
                .map(AccountSummary::of).collect(Collectors.toMap(AccountSummary::email, Function.identity()));
    }

    /** As {@link #findByEmail}, but empty instead of an error when the text is not an email address. */
    @Transactional(readOnly = true)
    public Optional<AccountSummary> findByEmailIfValid(String text) {
        try {
            return findByEmail(text);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public Optional<AccountSummary> findByEmail(String email) {
        return accounts.findByEmail(EmailAddress.normalize(email)).map(AccountSummary::of);
    }
}
