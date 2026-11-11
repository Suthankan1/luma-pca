package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.PasswordPolicyException;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class InvitationServiceIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSPHRASE = "quiet river under bridge";
    private static final int THREADS = 8;

    @Autowired
    AccountService accounts;
    @Autowired
    InvitationService invitations;
    @Autowired
    PasswordHasher hasher;
    @Autowired
    JdbcTemplate jdbc;

    UUID academyId;
    UUID studentId;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM academies");
        academyId = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Test Academy')", academyId);
        studentId = accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT)).id();
    }

    private Map<String, Object> account() {
        return jdbc.queryForMap("SELECT * FROM accounts WHERE id = ?", studentId);
    }

    private int openInvitations() {
        return jdbc.queryForObject("SELECT count(*) FROM account_invitations WHERE account_id = ? "
                + "AND consumed_at IS NULL AND revoked_at IS NULL", Integer.class, studentId);
    }

    private <T> List<Object> race(Callable<T> action) throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> futures = pool.invokeAll(IntStream.range(0, THREADS).<Callable<T>>mapToObj(i -> () -> {
                start.await();
                return action.call();
            }).toList());
            List<Object> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void issueReturnsRandomTokenAndStoresOnlyItsDigest() {
        UUID adminId = accounts.register(academyId, "admin@example.invalid", Set.of(Role.ACADEMY_ADMIN)).id();
        Instant before = Instant.now();

        IssuedInvitation issued = invitations.issue(studentId, adminId);

        assertTrue(issued.token().matches("[A-Za-z0-9_-]{43}"), "256-bit base64url token expected");
        assertFalse(issued.toString().contains(issued.token()));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM account_invitations WHERE account_id = ?", studentId);
        String stored = (String) row.get("token_hash");
        assertTrue(stored.matches("[0-9a-f]{64}"));
        assertNotEquals(issued.token(), stored);
        assertEquals(adminId, row.get("created_by"));
        assertNull(row.get("consumed_at"));
        Duration lifetime = Duration.between(before, issued.expiresAt());
        assertTrue(lifetime.compareTo(Duration.ofHours(71)) > 0 && lifetime.compareTo(Duration.ofHours(73)) < 0);
        assertEquals("PENDING_ACTIVATION", account().get("status"));
    }

    @Test
    void acceptActivatesAccountWithChosenPassword() {
        IssuedInvitation issued = invitations.issue(studentId, null);

        AccountSummary activated = invitations.accept(issued.token(), PASSPHRASE);

        assertEquals(studentId, activated.id());
        assertEquals(AccountStatus.ACTIVE, activated.status());
        assertTrue(hasher.matches(PASSPHRASE, (String) account().get("password_hash")));
        assertEquals(1, account().get("auth_version"));
        assertEquals(0, openInvitations());
        assertNotNull(jdbc.queryForObject(
                "SELECT consumed_at FROM account_invitations WHERE account_id = ?", Object.class, studentId));
    }

    @Test
    void tokenCannotBeUsedTwice() {
        IssuedInvitation issued = invitations.issue(studentId, null);
        invitations.accept(issued.token(), PASSPHRASE);

        assertThrows(InvalidInvitationException.class,
                () -> invitations.accept(issued.token(), "a different long passphrase"));

        assertTrue(hasher.matches(PASSPHRASE, (String) account().get("password_hash")));
        assertEquals(1, account().get("auth_version"));
    }

    @Test
    void unknownOrMalformedTokensAreRejectedIdentically() {
        IssuedInvitation issued = invitations.issue(studentId, null);
        String flipped = (issued.token().charAt(0) == 'A' ? "B" : "A") + issued.token().substring(1);

        for (String token : new String[] {null, "", "not-a-token", flipped, issued.token() + "x", "x".repeat(10_000)}) {
            InvalidInvitationException e = assertThrows(InvalidInvitationException.class,
                    () -> invitations.accept(token, PASSPHRASE));
            assertEquals("Invitation is invalid or has expired", e.getMessage());
        }
        assertEquals("PENDING_ACTIVATION", account().get("status"));
        assertEquals(1, openInvitations());
    }

    @Test
    void expiredInvitationIsRejected() {
        IssuedInvitation issued = invitations.issue(studentId, null);
        jdbc.update("UPDATE account_invitations SET created_at = NOW() - INTERVAL '2 days', "
                + "expires_at = NOW() - INTERVAL '1 second' WHERE account_id = ?", studentId);

        assertThrows(InvalidInvitationException.class, () -> invitations.accept(issued.token(), PASSPHRASE));

        assertEquals("PENDING_ACTIVATION", account().get("status"));
        assertNull(account().get("password_hash"));
    }

    @Test
    void rejectedPasswordLeavesInvitationUsable() {
        IssuedInvitation issued = invitations.issue(studentId, null);

        assertThrows(PasswordPolicyException.class, () -> invitations.accept(issued.token(), "too-short"));

        assertEquals(1, openInvitations());
        assertEquals("PENDING_ACTIVATION", account().get("status"));
        assertEquals(AccountStatus.ACTIVE, invitations.accept(issued.token(), PASSPHRASE).status());
    }

    @Test
    void reissuingRevokesTheEarlierToken() {
        IssuedInvitation first = invitations.issue(studentId, null);
        IssuedInvitation second = invitations.issue(studentId, null);

        assertNotEquals(first.token(), second.token());
        assertEquals(1, openInvitations());
        assertThrows(InvalidInvitationException.class, () -> invitations.accept(first.token(), PASSPHRASE));
        assertEquals("PENDING_ACTIVATION", account().get("status"));
        assertEquals(AccountStatus.ACTIVE, invitations.accept(second.token(), PASSPHRASE).status());
    }

    @Test
    void revokedInvitationIsRejected() {
        IssuedInvitation issued = invitations.issue(studentId, null);

        invitations.revoke(studentId);

        assertEquals(0, openInvitations());
        assertThrows(InvalidInvitationException.class, () -> invitations.accept(issued.token(), PASSPHRASE));
    }

    @Test
    void invitationForAccountDisabledAfterIssueIsRejected() {
        IssuedInvitation issued = invitations.issue(studentId, null);
        accounts.disable(studentId);

        assertThrows(InvalidInvitationException.class, () -> invitations.accept(issued.token(), PASSPHRASE));

        assertEquals("DISABLED", account().get("status"));
        assertNull(account().get("password_hash"));
        assertEquals(1, openInvitations(), "the failed claim must roll back");
    }

    @Test
    void onlyPendingExistingAccountsCanBeInvited() {
        assertThrows(AccountNotFoundException.class, () -> invitations.issue(UUID.randomUUID(), null));

        accounts.setPassword(studentId, PASSPHRASE);
        assertThrows(IllegalStateException.class, () -> invitations.issue(studentId, null));

        accounts.disable(studentId);
        assertThrows(IllegalStateException.class, () -> invitations.issue(studentId, null));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_invitations", Integer.class));
    }

    @Test
    void lifetimeMustBePositiveAndBounded() {
        for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofMinutes(-1), InvitationService.MAX_TTL.plusSeconds(1)}) {
            assertThrows(IllegalArgumentException.class, () -> invitations.issue(studentId, null, ttl));
        }
        assertNotNull(invitations.issue(studentId, null, InvitationService.MAX_TTL).token());
    }

    @Test
    void concurrentAcceptsOfOneTokenActivateExactlyOnce() throws Exception {
        IssuedInvitation issued = invitations.issue(studentId, null);

        List<Object> outcomes = race(() -> invitations.accept(issued.token(), PASSPHRASE));

        assertEquals(1, outcomes.stream().filter(AccountSummary.class::isInstance).count());
        assertEquals(THREADS - 1, outcomes.stream().filter(InvalidInvitationException.class::isInstance).count());
        assertEquals(1, account().get("auth_version"));
        assertEquals("ACTIVE", account().get("status"));
    }

    @Test
    void concurrentIssuesLeaveExactlyOneUsableInvitation() throws Exception {
        List<Object> outcomes = race(() -> invitations.issue(studentId, null));

        assertEquals(THREADS, outcomes.stream().filter(IssuedInvitation.class::isInstance).count(), outcomes::toString);
        assertEquals(1, openInvitations());
        long usable = outcomes.stream().map(IssuedInvitation.class::cast).filter(issued -> {
            try {
                invitations.accept(issued.token(), PASSPHRASE);
                return true;
            } catch (InvalidInvitationException e) {
                return false;
            }
        }).count();
        assertEquals(1, usable);
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        invitations.issue(studentId, null);
        String insert = "INSERT INTO account_invitations (id, account_id, token_hash, created_at, expires_at) "
                + "VALUES (?, ?, ?, NOW(), NOW() + ?::interval)";
        String digest = "ab".repeat(32);

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, UUID.randomUUID(), studentId, digest, "1 hour"), "second open invitation");
        UUID otherId = accounts.register(academyId, "other@example.invalid", Set.of(Role.STUDENT)).id();
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, UUID.randomUUID(), otherId, "raw-token-not-a-digest", "1 hour"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, UUID.randomUUID(), otherId, digest, "-1 hour"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), digest, "1 hour"));
        assertEquals(1, jdbc.update(insert, UUID.randomUUID(), otherId, digest, "1 hour"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE account_invitations SET consumed_at = NOW(), revoked_at = NOW() WHERE account_id = ?", otherId));
    }
}
