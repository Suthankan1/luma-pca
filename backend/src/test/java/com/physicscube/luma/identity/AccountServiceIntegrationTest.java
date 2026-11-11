package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.PasswordPolicyException;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountServiceIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSWORD = "quiet river under bridge";

    @Autowired
    AccountService accounts;
    @Autowired
    PasswordHasher hasher;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    UUID academyId;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM academies");
        academyId = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Test Academy')", academyId);
    }

    private Map<String, Object> row(UUID accountId) {
        return jdbc.queryForMap("SELECT * FROM accounts WHERE id = ?", accountId);
    }

    @Test
    void flywayAppliesAccountMigrationOnPostgres17() {
        assertTrue(jdbc.queryForObject("SELECT version()", String.class).startsWith("PostgreSQL 17"));
        assertEquals(List.of("1", "2"), jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class));
    }

    @Test
    void registerStoresNormalizedPendingAccountWithoutPassword() {
        AccountSummary created = accounts.register(academyId, "  New.Student@Example.INVALID ", Set.of(Role.STUDENT));

        assertEquals("new.student@example.invalid", created.email());
        assertEquals(AccountStatus.PENDING_ACTIVATION, created.status());
        assertEquals(Set.of(Role.STUDENT), created.roles());
        Map<String, Object> row = row(created.id());
        assertEquals(academyId, row.get("academy_id"));
        assertNull(row.get("password_hash"));
        assertEquals(List.of("STUDENT"), jdbc.queryForList(
                "SELECT role FROM account_roles WHERE account_id = ?", String.class, created.id()));
        assertEquals(created, accounts.findByEmail("NEW.student@example.invalid").orElseThrow());
    }

    @Test
    void accountMayHoldSeveralRoles() {
        AccountSummary created = accounts.register(academyId, "staff@example.invalid",
                Set.of(Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER));
        assertEquals(Set.of(Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER),
                accounts.findByEmail("staff@example.invalid").orElseThrow().roles());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM account_roles WHERE account_id = ?", Integer.class, created.id()));
    }

    @Test
    void registerRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> accounts.register(academyId, "not-an-email", Set.of(Role.STUDENT)));
        assertThrows(IllegalArgumentException.class, () -> accounts.register(academyId, "a@example.invalid", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> accounts.register(null, "a@example.invalid", Set.of(Role.STUDENT)));
        assertThrows(DataIntegrityViolationException.class,
                () -> accounts.register(UUID.randomUUID(), "a@example.invalid", Set.of(Role.STUDENT)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
    }

    @Test
    void emailIsUniqueRegardlessOfCaseAndAcademy() {
        accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT));
        UUID otherAcademy = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Other Academy')", otherAcademy);

        assertThrows(DuplicateEmailException.class,
                () -> accounts.register(academyId, "STUDENT@example.invalid", Set.of(Role.INSTRUCTOR)));
        assertThrows(DuplicateEmailException.class,
                () -> accounts.register(otherAcademy, "student@example.invalid", Set.of(Role.STUDENT)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
    }

    @Test
    void concurrentRegistrationOfOneEmailCreatesExactlyOneAccount() throws Exception {
        int contenders = 8;
        CyclicBarrier start = new CyclicBarrier(contenders);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<AccountSummary>> results = pool.invokeAll(IntStream.range(0, contenders)
                    .<Callable<AccountSummary>>mapToObj(i -> () -> {
                        start.await();
                        return accounts.register(academyId, "race@example.invalid", Set.of(Role.STUDENT));
                    }).toList());
            int created = 0;
            for (Future<AccountSummary> result : results) {
                try {
                    result.get();
                    created++;
                } catch (ExecutionException e) {
                    assertEquals(DuplicateEmailException.class, e.getCause().getClass());
                }
            }
            assertEquals(1, created);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
    }

    @Test
    void setPasswordActivatesAccountAndStoresOnlyAnArgon2idHash() {
        AccountSummary created = accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT));

        accounts.setPassword(created.id(), PASSWORD);

        Map<String, Object> row = row(created.id());
        String hash = (String) row.get("password_hash");
        assertTrue(hash.startsWith("$argon2id$"));
        assertFalse(hash.contains(PASSWORD));
        assertTrue(hasher.matches(PASSWORD, hash));
        assertFalse(hasher.matches("another long password", hash));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(1, row.get("auth_version"));
        assertTrue(row.get("password_changed_at") != null);
    }

    @Test
    void changingPasswordReplacesHashAndInvalidatesEarlierAuthVersion() {
        AccountSummary created = accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT));
        accounts.setPassword(created.id(), PASSWORD);
        jdbc.update("UPDATE accounts SET failed_login_count = 4, locked_until = NOW() + INTERVAL '5 minutes' WHERE id = ?",
                created.id());

        accounts.setPassword(created.id(), "a different long password");

        Map<String, Object> row = row(created.id());
        assertFalse(hasher.matches(PASSWORD, (String) row.get("password_hash")));
        assertTrue(hasher.matches("a different long password", (String) row.get("password_hash")));
        assertEquals(2, row.get("auth_version"));
        assertEquals(0, row.get("failed_login_count"));
        assertNull(row.get("locked_until"));
    }

    @Test
    void weakPasswordIsRejectedAndAccountStaysPending() {
        AccountSummary created = accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT));

        assertThrows(PasswordPolicyException.class, () -> accounts.setPassword(created.id(), "too-short"));
        assertThrows(PasswordPolicyException.class, () -> accounts.setPassword(created.id(), "student-is-my-name"));

        Map<String, Object> row = row(created.id());
        assertNull(row.get("password_hash"));
        assertEquals("PENDING_ACTIVATION", row.get("status"));
        assertEquals(0, row.get("auth_version"));
    }

    @Test
    void disablingBumpsAuthVersionOnceAndBlocksPasswordChanges() {
        AccountSummary created = accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT));
        accounts.setPassword(created.id(), PASSWORD);

        accounts.disable(created.id());
        accounts.disable(created.id());

        AccountSummary disabled = accounts.findByEmail("student@example.invalid").orElseThrow();
        assertEquals(AccountStatus.DISABLED, disabled.status());
        assertEquals(2, disabled.authVersion());
        assertThrows(IllegalStateException.class, () -> accounts.setPassword(created.id(), "a different long password"));
        assertTrue(hasher.matches(PASSWORD, (String) row(created.id()).get("password_hash")));
    }

    @Test
    void unknownAccountsAreReported() {
        UUID missing = UUID.randomUUID();
        assertThrows(AccountNotFoundException.class, () -> accounts.setPassword(missing, PASSWORD));
        assertThrows(AccountNotFoundException.class, () -> accounts.disable(missing));
        assertTrue(accounts.findByEmail("nobody@example.invalid").isEmpty());
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        String insert = "INSERT INTO accounts (id, academy_id, email, password_hash, status) VALUES (?, ?, ?, ?, ?)";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert,
                UUID.randomUUID(), academyId, "Mixed.Case@example.invalid", null, "PENDING_ACTIVATION"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert,
                UUID.randomUUID(), academyId, "active@example.invalid", null, "ACTIVE"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert,
                UUID.randomUUID(), academyId, "status@example.invalid", null, "SUPERUSER"));

        UUID accountId = accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT)).id();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO account_roles (account_id, role) VALUES (?, 'ROOT')", accountId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO account_roles (account_id, role) VALUES (?, 'STUDENT')", accountId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "DELETE FROM academies WHERE id = ?", academyId));
    }

    @Test
    void accountsAreNotExposedOverHttp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String path : List.of("/api/v1/accounts", "/api/v1/auth/login", "/api/v1/identity/accounts")) {
            for (String method : List.of("GET", "POST")) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + path))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(response.statusCode() == 401 || response.statusCode() == 403,
                        method + " " + path + " returned " + response.statusCode());
            }
        }
        assertEquals(200, client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/system/status")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
