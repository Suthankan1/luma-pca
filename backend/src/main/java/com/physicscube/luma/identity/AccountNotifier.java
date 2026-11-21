package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.Account;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * For other modules: short email notices to account holders, sent after the surrounding
 * transaction commits (so a rolled-back change sends nothing) on the account-mail queue. Does
 * nothing when email is not enabled or the account is not active. Never logs addresses.
 */
@Service
public class AccountNotifier {
    private final AccountRepository accounts;
    private final AccountMailer mailer;
    private final MailDelivery delivery;
    private final MailProperties properties;

    AccountNotifier(AccountRepository accounts, AccountMailer mailer, MailDelivery delivery, MailProperties properties) {
        this.accounts = accounts;
        this.mailer = mailer;
        this.delivery = delivery;
        this.properties = properties;
    }

    /** The web app's address, for "open LUMA" lines; {@code null} when not configured. */
    public String webOrigin() {
        return properties.webOrigin();
    }

    /** @param purpose a fixed word for logs, such as "appeal-decided"; never personal data */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void notify(UUID accountId, String purpose, String subject, String text) {
        if (!properties.enabled()) {
            return;
        }
        accounts.findById(accountId).filter(a -> a.getStatus() == AccountStatus.ACTIVE).map(Account::getEmail)
                .ifPresent(email -> delivery.afterCommit(accountId, purpose, () -> mailer.notice(email, subject, text)));
    }

    /** Active staff of the academy, for notices that need someone to act. */
    @Transactional(readOnly = true)
    public List<UUID> activeStaff(UUID academyId) {
        return accounts.withAnyRole(academyId, Set.of(Role.STAFF)).stream()
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE).map(Account::getId).toList();
    }
}
