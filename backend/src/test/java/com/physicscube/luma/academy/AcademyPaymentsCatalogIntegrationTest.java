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

/**
 * Synthetic Academy records (the real table is public.payment) in a disposable PostgreSQL database, read through a
 * role that may select only the granted columns and whose transactions are read-only, as the Academy set it up.
 */
class AcademyPaymentsCatalogIntegrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static JdbcTemplate owner;
    private static JdbcTemplate readonly;

    @BeforeAll
    static void createSyntheticAcademyTables() {
        POSTGRES.start();
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        owner.execute("""
                CREATE TABLE public.payment (
                    id BIGSERIAL PRIMARY KEY,
                    pcaid TEXT,
                    type TEXT,
                    class_type TEXT,
                    package_type TEXT,
                    payment NUMERIC(10, 2),
                    deleted_at TIMESTAMPTZ
                )
                """);
        owner.execute("""
                CREATE TABLE public.student (
                    id BIGSERIAL PRIMARY KEY,
                    pcaid TEXT,
                    name TEXT,
                    mail TEXT,
                    nic TEXT,
                    address TEXT,
                    deleted_at TIMESTAMPTZ
                )
                """);
        owner.execute("CREATE TABLE public.attendance (pcaid TEXT, present BOOLEAN)");
        owner.execute("CREATE ROLE luma_academy_readonly LOGIN PASSWORD 'calm lake reader words' NOINHERIT");
        owner.execute("GRANT USAGE ON SCHEMA public TO luma_academy_readonly");
        owner.execute("GRANT SELECT (pcaid, type, class_type, deleted_at) ON public.payment TO luma_academy_readonly");
        owner.execute("GRANT SELECT (pcaid, name, mail, deleted_at) ON public.student TO luma_academy_readonly");
        owner.execute("ALTER ROLE luma_academy_readonly SET default_transaction_read_only = on");
        readonly = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "luma_academy_readonly", "calm lake reader words"));

        add("ST-001", "class", " Physics 2026 ", false);
        add("st-001 ", "Class", "Physics 2026", false); // two payments, one student (case and spaces differ)
        add("ST-002", "class", "Physics 2026", false);
        add("ST-001", "class", "Revision 2026", false); // student in two classes
        add("ST-003", "class", "Physics 2026", true); // deleted row
        add("ST-004", "package", "Physics 2026", false); // not a class payment
        add("ST-005", "class", " ", false); // no class
        add(" ", "class", "Physics 2026", false); // no student ID
        add("ST-006", "class", "Physics 2026", false); // no student details
        add("ST-007", "class", "Physics 2026", false); // two live student rows: ambiguous
        add("ST-008", "class", "Admission", false); // administrative label: kept, not filtered silently

        student("ST-001", "Amaya Perera", "Amaya@Example.invalid", false);
        student("ST-002", "Old Name", "old@example.invalid", true); // deleted row ignored
        student("ST-002", "Kavin Silva", null, false);
        student("ST-007", "Twin One", "one@example.invalid", false);
        student("ST-007", "Twin Two", "two@example.invalid", false);
    }

    private static void add(String studentId, String type, String className, boolean deleted) {
        owner.update("INSERT INTO public.payment (pcaid, type, class_type, payment, deleted_at) VALUES (?, ?, ?, 2500, ?)",
                studentId, type, className, deleted ? java.sql.Timestamp.from(java.time.Instant.parse("2026-11-01T00:00:00Z")) : null);
    }

    private static void student(String studentId, String name, String mail, boolean deleted) {
        owner.update("INSERT INTO public.student (pcaid, name, mail, nic, address, deleted_at) VALUES (?, ?, ?, 'withheld', 'withheld', ?)",
                studentId, name, mail, deleted ? java.sql.Timestamp.from(java.time.Instant.parse("2026-11-01T00:00:00Z")) : null);
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    private static AcademyPaymentsCatalog catalog(String studentTable) {
        return new AcademyPaymentsCatalog(readonly, () -> {}, "public.payment", studentTable, "class");
    }

    @Test
    void listsUniqueClassesAndDistinctStudentsWithoutDeletedOrPackageRows() throws Exception {
        try (AcademyPaymentsCatalog catalog = catalog("public.student")) {
            assertEquals(List.of(
                    new AcademyPaymentClass("Admission", 1),
                    new AcademyPaymentClass("Physics 2026", 4),
                    new AcademyPaymentClass("Revision 2026", 1)), catalog.listClasses());
        }
    }

    @Test
    void returnsOnlyDistinctStudentIdsFromSelectedClass() throws Exception {
        try (AcademyPaymentsCatalog catalog = catalog("public.student")) {
            assertEquals(List.of("ST-001", "ST-002", "ST-006", "ST-007"), catalog.studentIdsForClass(" Physics 2026 "));
            assertEquals(List.of(), catalog.studentIdsForClass("Physics 2026' OR '1'='1"));
            assertThrows(IllegalArgumentException.class, () -> catalog.studentIdsForClass("   "));
        }
    }

    @Test
    void rosterHasNamesAndEmailsOnlyWhenOneLiveStudentRowMatches() throws Exception {
        try (AcademyPaymentsCatalog catalog = catalog("public.student")) {
            assertEquals(List.of(
                    new AcademyStudent("ST-001", "Amaya Perera", "Amaya@Example.invalid", 1),
                    new AcademyStudent("ST-002", "Kavin Silva", null, 1),
                    new AcademyStudent("ST-006", null, null, 0),
                    new AcademyStudent("ST-007", null, null, 2)), catalog.rosterForClass("Physics 2026"));
        }
    }

    @Test
    void withoutAStudentTableTheRosterIsIdsOnly() throws Exception {
        try (AcademyPaymentsCatalog catalog = catalog("")) {
            assertEquals(List.of(new AcademyStudent("ST-001", null, null, 0)), catalog.rosterForClass("Revision 2026"));
        }
    }

    @Test
    void theReadOnlyRoleCannotReadOtherColumnsOrTablesOrWrite() {
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> readonly.queryForList("SELECT payment FROM public.payment"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> readonly.queryForList("SELECT nic FROM public.student"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> readonly.queryForList("SELECT * FROM public.attendance"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> readonly.update("UPDATE public.payment SET class_type = 'x'"));
    }

    @Test
    void tableNamesOutsideTheStrictPatternAreRefused() {
        assertThrows(IllegalStateException.class,
                () -> new AcademyPaymentsCatalog(readonly, () -> {}, "public.payment; DROP TABLE x", "", "class"));
        assertThrows(IllegalStateException.class,
                () -> new AcademyPaymentsCatalog(readonly, () -> {}, "payment", "", "class"));
        assertThrows(IllegalStateException.class,
                () -> new AcademyPaymentsCatalog(readonly, () -> {}, "public.payment", "public.\"student\"", "class"));
    }
}
