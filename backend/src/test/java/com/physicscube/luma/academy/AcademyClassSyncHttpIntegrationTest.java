package com.physicscube.luma.academy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Academy's classes (synthetic rows in a separate schema, read through a column-limited read-only role) linked
 * to LUMA batches and synced into enrolments, over real HTTP.
 */
class AcademyClassSyncHttpIntegrationTest extends StaffApiIntegrationTest {
    private static JdbcTemplate source;

    @DynamicPropertySource
    static void academySource(DynamicPropertyRegistry registry) {
        registry.add("luma.academy-payments.enabled", () -> "true");
        registry.add("luma.academy-payments.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("luma.academy-payments.username", () -> "academy_sync_reader");
        registry.add("luma.academy-payments.password", () -> "calm lake reader words");
        registry.add("luma.academy-payments.payment-table", () -> "academy_src.payment");
        registry.add("luma.academy-payments.student-table", () -> "academy_src.student");
    }

    @BeforeAll
    static void createSource() {
        source = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        source.execute("DROP SCHEMA IF EXISTS academy_src CASCADE");
        source.execute("CREATE SCHEMA academy_src");
        source.execute("CREATE TABLE academy_src.payment (pcaid TEXT, type TEXT, class_type TEXT, payment NUMERIC, deleted_at TIMESTAMPTZ)");
        source.execute("CREATE TABLE academy_src.student (pcaid TEXT, name TEXT, mail TEXT, nic TEXT, deleted_at TIMESTAMPTZ)");
        source.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'academy_sync_reader') THEN "
                + "CREATE ROLE academy_sync_reader LOGIN PASSWORD 'calm lake reader words'; END IF; END $$");
        source.execute("GRANT USAGE ON SCHEMA academy_src TO academy_sync_reader");
        source.execute("GRANT SELECT (pcaid, type, class_type, deleted_at) ON academy_src.payment TO academy_sync_reader");
        source.execute("GRANT SELECT (pcaid, name, mail, deleted_at) ON academy_src.student TO academy_sync_reader");
        source.execute("ALTER ROLE academy_sync_reader SET default_transaction_read_only = on");
    }

    /** The database is shared with every other test (a backup test restores it whole): leave nothing behind. */
    @AfterAll
    static void dropSource() {
        source.execute("DROP SCHEMA IF EXISTS academy_src CASCADE");
        try {
            source.execute("DROP OWNED BY academy_sync_reader");
            source.execute("DROP ROLE IF EXISTS academy_sync_reader");
        } catch (org.springframework.dao.DataAccessException ignored) {
            // A connection still open as the reader keeps the role; with the schema gone it owns and can read nothing.
        }
    }

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batch;

    @BeforeEach
    void seed() {
        source.execute("TRUNCATE academy_src.payment, academy_src.student");
        pay("PCA001", "Physics 2027");
        pay("PCA001", "Physics 2027"); // second payment, same student
        pay("PCA002", "Physics 2027");
        pay("PCA003", "Physics 2027");
        pay("PCA004", "Physics 2027");
        pay("PCA005", "Physics 2027");
        pay("PCA001", "Revision 2027");
        details("PCA001", "Amaya Perera", "Amaya@Example.invalid");
        details("PCA002", "Kavin Silva", "family@example.invalid"); // siblings share one email
        details("PCA003", "Nila Silva", "family@example.invalid");
        details("PCA005", "Taken Email", "existing@example.invalid");
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "ACAD");
    }

    private static void pay(String id, String className) {
        source.update("INSERT INTO academy_src.payment (pcaid, type, class_type, payment) VALUES (?, 'class', ?, 3000)", id, className);
    }

    private static void details(String id, String name, String mail) {
        source.update("INSERT INTO academy_src.student (pcaid, name, mail, nic) VALUES (?, ?, ?, 'not-for-luma')", id, name, mail);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return (List<Map<String, Object>>) (List<?>) HttpApi.parseList(response);
    }

    private HttpResponse<String> link(String className, String batchId, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("className", className);
        body.put("batchId", batchId);
        return api.put("/api/v1/academy/classes/link", HttpApi.json(body), token);
    }

    private HttpResponse<String> sync(String className, String token) {
        return api.post("/api/v1/academy/classes/sync", HttpApi.json(Map.of("className", className)), token);
    }

    @Test
    void classesAreListedWithDistinctStudentCountsThenLinkedAndSyncedIntoTheBatch() {
        fixtures.student("PCA-OTHER", "existing@example.invalid"); // takes PCA005's email
        List<Map<String, Object>> classes = maps(api.get("/api/v1/academy/classes", admin));
        assertEquals(2, classes.size());
        assertEquals("Physics 2027", classes.get(0).get("className"));
        assertEquals(5, classes.get(0).get("studentCount"), "payments are not students");
        assertNull(classes.get(0).get("batch"));

        HttpResponse<String> linked = link("Physics 2027", batch, admin);
        assertEquals(200, linked.statusCode(), linked.body());

        HttpResponse<String> first = sync("Physics 2027", admin);
        assertEquals(200, first.statusCode(), first.body());
        Map<String, Object> result = HttpApi.parse(first);
        assertEquals(5, result.get("academyStudents"));
        assertEquals(5, result.get("created"));
        assertEquals(5, result.get("enrolled"));
        assertEquals(1, result.get("withoutName"));
        assertEquals(4, result.get("withoutEmail"), "shared, taken and missing emails are not used");

        List<Map<String, Object>> roster = maps(api.get("/api/v1/batches/" + batch + "/enrollments", admin));
        assertEquals(5, roster.size());
        Map<String, String> emails = new LinkedHashMap<>();
        jdbc.query("SELECT s.student_number, s.full_name, a.email FROM students s JOIN accounts a ON a.id = s.account_id "
                + "WHERE s.academy_id = ? AND s.student_number LIKE 'PCA0%' ORDER BY 1", row -> {
            emails.put(row.getString(1), row.getString(3));
        }, academyId);
        assertEquals("amaya@example.invalid", emails.get("PCA001"), "a unique Academy email lets Zoom match the student");
        assertEquals("pca002@students.academy.invalid", emails.get("PCA002"));
        assertEquals("pca003@students.academy.invalid", emails.get("PCA003"));
        assertEquals("pca005@students.academy.invalid", emails.get("PCA005"));
        assertEquals("PCA004", jdbc.queryForObject("SELECT full_name FROM students WHERE academy_id = ? AND student_number = 'PCA004'",
                String.class, academyId), "no name: shown by Academy ID");

        // Repeating changes nothing.
        Map<String, Object> again = HttpApi.parse(sync("Physics 2027", admin));
        assertEquals(0, again.get("created"));
        assertEquals(0, again.get("enrolled"));
        assertEquals(5, again.get("alreadyEnrolled"));

        // A student leaves the Academy's class: reported, not withdrawn.
        source.update("UPDATE academy_src.payment SET deleted_at = now() WHERE pcaid = 'PCA004'");
        Map<String, Object> later = HttpApi.parse(sync("Physics 2027", admin));
        assertEquals(List.of("PCA004"), later.get("notInAcademyClass"));
        assertEquals(5, maps(api.get("/api/v1/batches/" + batch + "/enrollments", admin)).size());

        Map<String, Object> listed = maps(api.get("/api/v1/academy/classes", admin)).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> linkedBatch = (Map<String, Object>) listed.get("batch");
        assertEquals(batch, linkedBatch.get("id"));
        assertEquals(4, linkedBatch.get("lastSyncedStudents"));

        String audit = jdbc.queryForObject("SELECT string_agg(details::text, ' ') FROM audit_events WHERE academy_id = ? "
                + "AND action LIKE 'ACADEMY_CLASS_%'", String.class, academyId);
        assertFalse(audit.contains("PCA0") || audit.contains("@") || audit.contains("Amaya"), "no personal data in the audit trail");
        assertTrue(audit.contains("Physics 2027"));
    }

    @Test
    void aStudentAlreadyInLumaIsEnrolledNotDuplicatedAndAStaffWithdrawalIsKept() {
        String existing = (String) fixtures.student("pca001", "amaya.own@example.invalid").get("id");
        String withdrawnStudent = (String) fixtures.student("PCA002", "kavin.own@example.invalid").get("id");
        String enrollment = (String) HttpApi.parse(fixtures.enroll(batch, withdrawnStudent, "2027-01-10")).get("id");
        Map<String, Object> withdraw = new LinkedHashMap<>();
        withdraw.put("enrolledOn", "2027-01-10");
        withdraw.put("withdrawnOn", "2027-01-11");
        withdraw.put("version", 0);
        assertEquals(200, api.put("/api/v1/enrollments/" + enrollment, HttpApi.json(withdraw), admin).statusCode());

        assertEquals(200, link("Physics 2027", batch, admin).statusCode());
        Map<String, Object> result = HttpApi.parse(sync("Physics 2027", admin));
        assertEquals(3, result.get("created"));
        assertEquals(4, result.get("enrolled"));
        assertEquals(List.of("PCA002"), result.get("withdrawnInLuma"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM students WHERE academy_id = ? AND student_number = 'PCA001'",
                Integer.class, academyId));
        assertEquals("amaya.own@example.invalid", jdbc.queryForObject("SELECT a.email FROM students s JOIN accounts a ON a.id = s.account_id "
                + "WHERE s.id = ?::uuid", String.class, existing), "an existing student's email is left alone");
    }

    @Test
    void onlyOwnersAndAdministratorsMayUseAcademyClassesAndLinksAreChecked() {
        String instructor = tokenFor(academyId, Role.INSTRUCTOR);
        String student = tokenFor(academyId, Role.STUDENT);
        for (String token : List.of(instructor, student)) {
            assertEquals(403, api.get("/api/v1/academy/classes", token).statusCode());
            assertEquals(403, link("Physics 2027", batch, token).statusCode());
            assertEquals(403, sync("Physics 2027", token).statusCode());
        }
        assertEquals(401, api.get("/api/v1/academy/classes", null).statusCode());

        assertEquals(404, link("No such class", batch, admin).statusCode());
        assertEquals(404, sync("Physics 2027", admin).statusCode(), "not linked yet");

        UUID otherAcademy = academy("Other academy");
        String otherAdmin = tokenFor(otherAcademy, Role.ACADEMY_ADMIN);
        assertEquals(404, link("Physics 2027", batch, otherAdmin).statusCode(), "another academy's batch");

        assertEquals(200, link("Physics 2027", batch, admin).statusCode());
        assertEquals(409, link("Revision 2027", batch, admin).statusCode(), "a batch stands for one Academy class");
        assertEquals(400, link(" ", batch, admin).statusCode());
    }
}
