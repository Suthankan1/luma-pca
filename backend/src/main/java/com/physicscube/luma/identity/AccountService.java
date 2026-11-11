package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.EmailAddress;
import com.physicscube.luma.identity.domain.PasswordPolicy;
import com.physicscube.luma.identity.domain.Role;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.UUID;
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

    @Transactional(readOnly = true)
    public Optional<AccountSummary> findByEmail(String email) {
        return accounts.findByEmail(EmailAddress.normalize(email)).map(AccountSummary::of);
    }
}
