package com.physicscube.luma.academy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Synthetic external-payment records in a disposable PostgreSQL database. */
class AcademyPaymentsCatalogIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createSyntheticAcademyTable() {
        POSTGRES.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("""
                CREATE TABLE public.payments (
                    id BIGSERIAL PRIMARY KEY,
                    pcaid TEXT,
                    type TEXT,
                    class_type TEXT,
                    deleted_at TIMESTAMPTZ
                )
                """);
        add("ST-001", "class", " Physics 2026 ", false);
        add("ST-001", "class", "Physics 2026", false); // two payments, one student
        add("ST-002", "class", "Physics 2026", false);
        add("ST-001", "class", "Revision 2026", false); // student in two classes
        add("ST-003", "class", "Physics 2026", true); // deleted row
        add("ST-004", "package", "Physics 2026", false); // not a class payment
        add("ST-005", "class", " ", false); // no class
        add(" ", "class", "Physics 2026", false); // no student ID
    }

    private static void add(String studentId, String type, String className, boolean deleted) {
        jdbc.update("""
                INSERT INTO public.payments (pcaid, type, class_type, deleted_at)
                VALUES (?, ?, ?, ?)
                """, studentId, type, className,
                deleted ? java.sql.Timestamp.from(java.time.Instant.parse("2026-11-01T00:00:00Z")) : null);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    void listsUniqueClassesAndDistinctStudentsWithoutDeletedOrPackageRows() throws Exception {
        try (AcademyPaymentsCatalog catalog = new AcademyPaymentsCatalog(jdbc, () -> {})) {
            assertEquals(List.of(
                    new AcademyPaymentClass("Physics 2026", 2),
                    new AcademyPaymentClass("Revision 2026", 1)), catalog.listClasses());
        }
    }

    @Test
    void returnsOnlyDistinctStudentIdsFromSelectedClass() throws Exception {
        try (AcademyPaymentsCatalog catalog = new AcademyPaymentsCatalog(jdbc, () -> {})) {
            assertEquals(List.of("ST-001", "ST-002"), catalog.studentIdsForClass(" Physics 2026 "));
            assertEquals(List.of(), catalog.studentIdsForClass("Physics 2026' OR '1'='1"));
            assertThrows(IllegalArgumentException.class, () -> catalog.studentIdsForClass("   "));
        }
    }
}
