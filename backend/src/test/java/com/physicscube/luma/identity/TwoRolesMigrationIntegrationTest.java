package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V30 (owner decision 2026-11-20: two roles) on a database migrated to V29 with every former role: each staff account
 * ends with one STAFF row granted when its earliest staff role was, students keep STUDENT, and only STAFF and STUDENT
 * can be stored afterwards. Runs in its own database so the shared test schema is untouched.
 */
class TwoRolesMigrationIntegrationTest extends PostgresIntegrationTest {

    @Test
    void everyFormerStaffRoleBecomesOneStaffRoleAndStudentsAreUnchanged() throws Exception {
        String database = "v30_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("29").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));

        UUID academy = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Migration Academy')", academy);
        UUID owner = account(jdbc, academy, "owner@example.invalid", "ACADEMY_OWNER", "ACADEMY_ADMIN");
        UUID instructor = account(jdbc, academy, "teacher@example.invalid", "INSTRUCTOR", "ATTENDANCE_OFFICER");
        UUID analyst = account(jdbc, academy, "analyst@example.invalid", "ANALYST");
        UUID student = account(jdbc, academy, "student@example.invalid", "STUDENT");
        UUID both = account(jdbc, academy, "both@example.invalid", "INSTRUCTOR", "STUDENT");
        jdbc.update("UPDATE account_roles SET granted_at = '2026-01-01T00:00:00Z' WHERE account_id = ? AND role = 'ACADEMY_ADMIN'", owner);
        jdbc.update("UPDATE account_roles SET granted_at = '2026-06-01T00:00:00Z' WHERE account_id = ? AND role = 'ACADEMY_OWNER'", owner);

        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        assertEquals(List.of("STAFF"), roles(jdbc, owner));
        assertEquals(List.of("STAFF"), roles(jdbc, instructor));
        assertEquals(List.of("STAFF"), roles(jdbc, analyst));
        assertEquals(List.of("STUDENT"), roles(jdbc, student));
        assertEquals(List.of("STAFF", "STUDENT"), roles(jdbc, both));
        assertEquals("2026-01-01", jdbc.queryForObject(
                "SELECT to_char(granted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') FROM account_roles WHERE account_id = ?", String.class, owner),
                "the earliest staff grant is kept");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_roles WHERE role NOT IN ('STAFF', 'STUDENT')", Integer.class));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO account_roles (account_id, role) VALUES (?, 'ACADEMY_OWNER')", student));
        Map<String, Object> comment = jdbc.queryForMap("SELECT obj_description('staff_assignments'::regclass, 'pg_class') AS c");
        assertEquals("Retired by V30 (two roles): kept for history, no longer read or written.", comment.get("c"));
    }

    private static UUID account(JdbcTemplate jdbc, UUID academy, String email, String... roles) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, academy_id, email, status) VALUES (?, ?, ?, 'PENDING_ACTIVATION')", id, academy, email);
        for (String role : roles) {
            jdbc.update("INSERT INTO account_roles (account_id, role) VALUES (?, ?)", id, role);
        }
        return id;
    }

    private static List<String> roles(JdbcTemplate jdbc, UUID account) {
        return jdbc.queryForList("SELECT role FROM account_roles WHERE account_id = ? ORDER BY role", String.class, account);
    }
}
