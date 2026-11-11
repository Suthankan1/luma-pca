package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Internal to the identity module; other modules go through {@link AccountService}. */
interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);
}
