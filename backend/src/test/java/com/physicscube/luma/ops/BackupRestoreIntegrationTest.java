package com.physicscube.luma.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Restore drill in CI: a pg_dump of a database holding data from every area restores into a
 * fresh PostgreSQL 17 completely — Flyway validates the copy against this build's migrations,
 * every table's contents are identical, and the audit log is still append-only.
 */
class BackupRestoreIntegrationTest extends StaffApiIntegrationTest {

    private void seed() {
        UUID academyId = academy("Restore Drill Academy");
        accountIdOf("owner@example.invalid", academyId, Role.STAFF);
        String owner = login("owner@example.invalid");
        String teacher = (String) com.physicscube.luma.support.HttpApi.parse(api.post("/api/v1/staff-accounts",
                "{\"email\":\"teacher@example.invalid\",\"roles\":[\"INSTRUCTOR\"]}", owner)).get("id");
        assertEquals(201, api.post("/api/v1/accounts/" + teacher + "/invitations", "", owner).statusCode());
        assertEquals(200, api.post("/api/v1/staff-accounts/" + teacher + "/disable", "", owner).statusCode());
        AcademyFixtures fixtures = new AcademyFixtures(api, owner);
        String batch = fixtures.batch(fixtures.course("PHY-2027"), "MON");
        assertEquals(200, api.post("/api/v1/students/import", "{\"rows\":[{\"studentNumber\":\"PCA-1\",\"fullName\":\"Ann Perera\","
                + "\"email\":\"ann@example.invalid\"},{\"studentNumber\":\"PCA-2\",\"fullName\":\"Ben Silva\",\"email\":\"ben@example.invalid\"}],"
                + "\"batchId\":\"" + batch + "\",\"enrolledOn\":\"2027-01-10\"}", owner).statusCode());
        assertEquals(200, api.post("/api/v1/class-sessions/series", "{\"title\":\"Theory\",\"firstDate\":\"2027-01-11\","
                + "\"lastDate\":\"2027-01-31\",\"weekdays\":[\"MONDAY\"],\"startTime\":\"18:00\",\"endTime\":\"20:00\","
                + "\"batchIds\":[\"" + batch + "\"],\"zoomWebinarId\":\"81234567890\"}", owner).statusCode());
    }

    /** Every public table's row count and a hash of its full contents, in a stable order. */
    private static Map<String, String> fingerprint(Connection connection) throws SQLException {
        Map<String, String> tables = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet names = statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")) {
            List<String> all = new java.util.ArrayList<>();
            while (names.next()) {
                all.add(names.getString(1));
            }
            for (String table : all) {
                try (Statement each = connection.createStatement();
                     ResultSet row = each.executeQuery("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '-') FROM \""
                             + table + "\" t")) {
                    row.next();
                    tables.put(table, row.getString(1));
                }
            }
        }
        return tables;
    }

    @Test
    void aDumpRestoresCompletelyAndTheAuditLogStaysAppendOnly() throws Exception {
        seed();
        ExecResult dump = POSTGRES.execInContainer("pg_dump", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-Fc", "--no-owner", "-f", "/tmp/luma.dump");
        assertEquals(0, dump.getExitCode(), dump.getStderr());
        Path local = Files.createTempFile("luma-restore-drill", ".dump");
        try (PostgreSQLContainer restored = new PostgreSQLContainer("postgres:17-alpine")) {
            POSTGRES.copyFileFromContainer("/tmp/luma.dump", local.toString());
            restored.start();
            restored.copyFileToContainer(MountableFile.forHostPath(local), "/tmp/luma.dump");
            ExecResult restore = restored.execInContainer("pg_restore", "-U", restored.getUsername(), "-d", restored.getDatabaseName(),
                    "--no-owner", "--exit-on-error", "/tmp/luma.dump");
            assertEquals(0, restore.getExitCode(), restore.getStderr());

            // The copy matches this build's migrations exactly (checksums included) and needs none applied.
            Flyway flyway = Flyway.configure().dataSource(restored.getJdbcUrl(), restored.getUsername(), restored.getPassword())
                    .locations("classpath:db/migration").load();
            flyway.validate();
            assertEquals(0, flyway.info().pending().length);

            try (Connection source = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Connection copy = DriverManager.getConnection(restored.getJdbcUrl(), restored.getUsername(), restored.getPassword())) {
                Map<String, String> expected = fingerprint(source);
                assertEquals(expected, fingerprint(copy), "every table's contents survive the restore");
                assertTrue(expected.get("students").startsWith("2:"));
                assertTrue(expected.get("class_sessions").startsWith("3:"));
                assertTrue(Integer.parseInt(expected.get("audit_events").split(":")[0]) >= 4);

                try (Statement statement = copy.createStatement()) {
                    SQLException refused = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                            () -> statement.executeUpdate("UPDATE audit_events SET action = action"));
                    assertTrue(refused.getMessage().contains("append-only"), refused.getMessage());
                }
            }
        } finally {
            Files.deleteIfExists(local);
        }
    }
}
