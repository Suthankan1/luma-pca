package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.Role;
import java.util.Set;
import java.util.UUID;

/** Read model for other modules. Deliberately excludes the password hash. */
public record AccountSummary(UUID id, UUID academyId, String email, AccountStatus status,
                             Set<Role> roles, int authVersion) {
    static AccountSummary of(Account account) {
        return new AccountSummary(account.getId(), account.getAcademyId(), account.getEmail(),
                account.getStatus(), account.getRoles(), account.getAuthVersion());
    }
}
