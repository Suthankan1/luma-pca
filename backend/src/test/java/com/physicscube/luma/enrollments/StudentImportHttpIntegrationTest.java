package com.physicscube.luma.enrollments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** POST /api/v1/students/import: all rows checked first, nothing written unless every row is valid. */
class StudentImportHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final String IMPORT = "/api/v1/students/import";

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batch;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "MON");
    }

    private static Map<String, Object> row(String number, String name, String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("studentNumber", number);
        row.put("fullName", name);
        row.put("email", email);
        return row;
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(row(String.format("pca-%04d", i), "Student " + i, String.format("Student.%04d@Example.invalid", i)));
        }
        return rows;
    }

    private HttpResponse<String> send(List<Map<String, Object>> rows, String batchId, String enrolledOn, boolean dryRun, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        if (batchId != null) {
            body.put("batchId", batchId);
        }
        if (enrolledOn != null) {
            body.put("enrolledOn", enrolledOn);
        }
        body.put("dryRun", dryRun);
        return api.post(IMPORT, toJson(body), token);
    }

    /** HttpApi.json only handles flat maps; the rows need a nested list. */
    private static String toJson(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int studentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM students WHERE academy_id = ?", Integer.class, academyId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> problems(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return (List<Map<String, Object>>) HttpApi.parse(response).get("problems");
    }

    @Test
    void aDryRunChecksWithoutWritingThenTheImportCreatesAndEnrolsEveryone() {
        List<Map<String, Object>> rows = rows(600);

        Map<String, Object> dry = HttpApi.parse(send(rows, batch, "2027-01-10", true, admin));
        assertEquals(Map.of("imported", false, "rows", 600, "enrolled", 0, "problems", List.of()), dry);
        assertEquals(0, studentCount());

        long started = System.nanoTime();
        HttpResponse<String> done = send(rows, batch, "2027-01-10", false, admin);
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(Map.of("imported", true, "rows", 600, "enrolled", 600, "problems", List.of()), HttpApi.parse(done));
        System.out.println("Imported 600 students with enrolments in " + millis + " ms");

        assertEquals(600, studentCount());
        assertEquals(600, HttpApi.parseList(api.get("/api/v1/batches/" + batch + "/enrollments", admin)).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) HttpApi.parse(api.get("/api/v1/students?limit=1", admin)).get("items")).get(0);
        assertEquals("PCA-0001", first.get("studentNumber"), "numbers are normalised like single creation");
        assertEquals("student.0001@example.invalid", first.get("email"));
        assertEquals("PENDING_ACTIVATION", first.get("accountStatus"), "accounts wait for an invitation, as for one student");
        assertEquals(600, jdbc.queryForObject("SELECT count(*) FROM account_roles r JOIN accounts a ON a.id = r.account_id "
                + "WHERE a.academy_id = ? AND r.role = 'STUDENT'", Integer.class, academyId));

        Map<String, Object> audit = jdbc.queryForMap("SELECT action, target_type, target_id::text AS target, details::text AS details "
                + "FROM audit_events WHERE academy_id = ? AND action = 'STUDENTS_IMPORTED'", academyId);
        assertEquals("BATCH", audit.get("target_type"));
        assertEquals(batch, audit.get("target"));
        String details = (String) audit.get("details");
        assertTrue(details.contains("\"students\": \"600\"") || details.contains("\"students\":\"600\""), details);
        assertFalse(details.toLowerCase().contains("pca-") || details.contains("@"), "no personal data in the audit trail");

        // The same file again: every row now clashes, and nothing more is written.
        List<Map<String, Object>> again = problems(send(rows.subList(0, 2), null, null, false, admin));
        assertEquals(List.of(
                Map.of("row", 1, "field", "email", "message", "An account with this email already exists"),
                Map.of("row", 1, "field", "studentNumber", "message", "A student with this student number already exists"),
                Map.of("row", 2, "field", "email", "message", "An account with this email already exists"),
                Map.of("row", 2, "field", "studentNumber", "message", "A student with this student number already exists")), again);
        assertEquals(600, studentCount());
    }

    @Test
    void everyProblemIsReportedByRowAndNothingIsWritten() {
        fixtures.student("PCA-OLD", "taken@example.invalid");
        List<Map<String, Object>> rows = List.of(
                row("PCA-1", "Good Student", "good@example.invalid"),
                row("-bad", "  ", "not-an-email"),
                row("pca-1", "Repeat Number", "other@example.invalid"),
                row("PCA-3", "Repeat Email", "GOOD@example.invalid"),
                row("pca-old", "Existing Number", "fresh@example.invalid"),
                row("PCA-5", "Existing Email", " Taken@Example.invalid "),
                row(null, "x".repeat(201), null));

        HttpResponse<String> response = send(rows, null, null, false, admin);

        assertFalse((Boolean) HttpApi.parse(response).get("imported"));
        assertEquals(List.of(
                Map.of("row", 2, "field", "email", "message", "Email is not a valid address"),
                Map.of("row", 2, "field", "fullName", "message", "A full name of up to 200 characters is required"),
                Map.of("row", 2, "field", "studentNumber", "message",
                        "Use up to 60 letters, digits, dots, dashes or underscores, starting with a letter or digit"),
                Map.of("row", 3, "field", "studentNumber", "message", "Same student number as row 1"),
                Map.of("row", 4, "field", "email", "message", "Same email as row 1"),
                Map.of("row", 5, "field", "studentNumber", "message", "A student with this student number already exists"),
                Map.of("row", 6, "field", "email", "message", "An account with this email already exists"),
                Map.of("row", 7, "field", "email", "message", "Email is not a valid address"),
                Map.of("row", 7, "field", "fullName", "message", "A full name of up to 200 characters is required"),
                Map.of("row", 7, "field", "studentNumber", "message",
                        "Use up to 60 letters, digits, dots, dashes or underscores, starting with a letter or digit")),
                problems(response));
        assertEquals(1, studentCount(), "only the student that existed before");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = 'good@example.invalid'", Integer.class));
    }

    @Test
    void refusals() {
        List<Map<String, Object>> one = rows(1);
        assertEquals(403, send(one, null, null, true, tokenFor(academyId, Role.STUDENT)).statusCode());
        assertEquals(401, send(one, null, null, true, null).statusCode());
        assertEquals(400, send(List.of(), null, null, true, admin).statusCode());
        assertEquals(400, send(rows(1001), null, null, true, admin).statusCode());
        assertEquals(400, send(one, batch, null, true, admin).statusCode(), "batch without a date");
        assertEquals(400, send(one, null, "2027-01-10", true, admin).statusCode(), "date without a batch");

        UUID otherAcademy = academy("Other Academy");
        String otherBatch = new AcademyFixtures(api, tokenFor(otherAcademy, Role.STAFF)).batch(
                new AcademyFixtures(api, tokenFor(otherAcademy, Role.STAFF)).course("OTH-1"), "X");
        assertEquals(404, send(one, otherBatch, "2027-01-10", false, admin).statusCode(), "another academy's batch");
        jdbc.update("UPDATE batches SET active = false WHERE id = ?::uuid", batch);
        assertEquals(409, send(one, batch, "2027-01-10", false, admin).statusCode(), "inactive batch");
        assertEquals(0, studentCount());
    }
}
