package com.physicscube.luma.enrollments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class EnrollmentHttpIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String physics;
    String batchA;
    String batchB;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        fixtures = new AcademyFixtures(api, admin);
        physics = fixtures.course("PHY-2027");
        batchA = fixtures.batch(physics, "A");
        batchB = fixtures.batch(physics, "B");
    }

    private String studentId(String number) {
        return (String) fixtures.student(number, number.toLowerCase() + "@example.invalid").get("id");
    }

    /** Creates an activated student and returns their access token. */
    private String signedInStudent(String number, String... batches) {
        Map<String, Object> student = fixtures.student(number, number.toLowerCase() + "@example.invalid");
        for (String batch : batches) {
            assertEquals(201, fixtures.enroll(batch, (String) student.get("id"), "2027-01-10").statusCode());
        }
        activate(UUID.fromString((String) student.get("accountId")));
        return login(number.toLowerCase() + "@example.invalid");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(item -> (Map<String, Object>) item).toList();
    }

    private static String updateBody(String enrolledOn, String withdrawnOn, int version) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("enrolledOn", enrolledOn);
        fields.put("withdrawnOn", withdrawnOn);
        fields.put("version", version);
        return HttpApi.json(fields);
    }

    @Test
    void adminEnrollsAStudentAndSeesTheRoster() {
        String student = studentId("PCA-002");
        String other = studentId("PCA-001");

        HttpResponse<String> response = fixtures.enroll(batchA, student, "2027-01-15");
        fixtures.enroll(batchA, other, "2027-01-10");

        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> created = HttpApi.parse(response);
        assertEquals(batchA, created.get("batchId"));
        assertEquals(physics, created.get("courseId"));
        assertEquals("A", created.get("batchCode"));
        assertEquals("PCA-002", created.get("studentNumber"));
        assertEquals("2027-01-15", created.get("enrolledOn"));
        assertEquals("ENROLLED", created.get("status"));
        assertEquals("/api/v1/enrollments/" + created.get("id"), response.headers().firstValue("Location").orElseThrow());
        assertEquals(List.of("PCA-001", "PCA-002"),
                list(api.get("/api/v1/batches/" + batchA + "/enrollments", admin)).stream().map(e -> e.get("studentNumber")).toList());
        assertEquals(List.of(), list(api.get("/api/v1/batches/" + batchB + "/enrollments", admin)));
    }

    @Test
    void oneStudentMayBelongToSeveralBatchesWithOneIdentity() {
        String chemistry = fixtures.batch(fixtures.course("CHEM-2027"), "A");
        String student = studentId("PCA-001");

        assertEquals(201, fixtures.enroll(batchA, student, "2027-01-10").statusCode());
        assertEquals(201, fixtures.enroll(batchB, student, "2027-01-10").statusCode(), "two batches of one course");
        assertEquals(201, fixtures.enroll(chemistry, student, "2027-01-10").statusCode());

        assertEquals(3, list(api.get("/api/v1/students/" + student + "/enrollments", admin)).size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM students", Integer.class), "no copy of the student per batch");
    }

    @Test
    void aStudentCannotBeEnrolledTwiceInOneBatchEvenConcurrently() throws Exception {
        String student = studentId("PCA-001");
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, threads).<Callable<Integer>>mapToObj(i -> () -> {
                start.await();
                return fixtures.enroll(batchA, student, "2027-01-10").statusCode();
            }).toList())) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(threads - 1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM enrollments", Integer.class));
    }

    @Test
    void inactiveForeignAndMissingRecordsAreRefused() {
        String student = studentId("PCA-001");
        Map<String, Object> deactivate = new LinkedHashMap<>();
        deactivate.put("code", "B");
        deactivate.put("name", "Batch B");
        deactivate.put("startsOn", "2027-01-10");
        deactivate.put("active", false);
        deactivate.put("version", 0);
        assertEquals(200, api.put("/api/v1/batches/" + batchB, HttpApi.json(deactivate), admin).statusCode());
        assertEquals(409, fixtures.enroll(batchB, student, "2027-01-10").statusCode(), "inactive batch");

        String leaver = studentId("PCA-002");
        Map<String, Object> inactive = new LinkedHashMap<>();
        inactive.put("fullName", "Leaver");
        inactive.put("active", false);
        inactive.put("version", 0);
        assertEquals(200, api.put("/api/v1/students/" + leaver, HttpApi.json(inactive), admin).statusCode());
        assertEquals(409, fixtures.enroll(batchA, leaver, "2027-01-10").statusCode(), "inactive student");

        String otherAdmin = tokenFor(academy("Other Academy"), Role.ACADEMY_ADMIN);
        AcademyFixtures theirs = new AcademyFixtures(api, otherAdmin);
        String theirBatch = theirs.batch(theirs.course("THEIRS"), "X");
        String theirStudent = (String) theirs.student("THEIR-1", "their@example.invalid").get("id");
        assertEquals(404, fixtures.enroll(theirBatch, student, "2027-01-10").statusCode(), "foreign batch");
        assertEquals(404, fixtures.enroll(batchA, theirStudent, "2027-01-10").statusCode(), "foreign student");
        assertEquals(404, fixtures.enroll(UUID.randomUUID().toString(), student, "2027-01-10").statusCode());
        assertEquals(400, fixtures.enroll(batchA, student, "not-a-date").statusCode());
        assertEquals(400, api.post("/api/v1/batches/" + batchA + "/enrollments", "{}", admin).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM enrollments", Integer.class));
    }

    @Test
    void withdrawalKeepsTheRecordAndCanBeUndone() {
        String student = studentId("PCA-001");
        String id = (String) HttpApi.parse(fixtures.enroll(batchA, student, "2027-01-10")).get("id");
        String path = "/api/v1/enrollments/" + id;

        Map<String, Object> withdrawn = HttpApi.parse(api.put(path, updateBody("2027-01-10", "2027-03-31", 0), admin));
        assertEquals("WITHDRAWN", withdrawn.get("status"));
        assertEquals("2027-03-31", withdrawn.get("withdrawnOn"));
        assertEquals(1, list(api.get("/api/v1/batches/" + batchA + "/enrollments", admin)).size(), "withdrawn stays on the roster");

        assertEquals(409, api.put(path, updateBody("2027-01-10", null, 0), admin).statusCode(), "stale version");
        assertEquals(400, api.put(path, updateBody("2027-04-01", "2027-03-31", 1), admin).statusCode(), "withdrawn before enrolled");
        Map<String, Object> back = HttpApi.parse(api.put(path, updateBody("2027-01-10", null, 1), admin));
        assertEquals("ENROLLED", back.get("status"));
        assertEquals(404, api.put("/api/v1/enrollments/" + UUID.randomUUID(), updateBody("2027-01-10", null, 0), admin).statusCode());
        assertEquals(409, fixtures.enroll(batchA, student, "2027-05-01").statusCode(), "re-enrol by clearing the withdrawal, not a second row");
    }

    @Test
    void aStudentSeesOnlyTheirOwnEnrollmentsAndNoRosters() {
        String mine = signedInStudent("PCA-001", batchA);
        String classmate = studentId("PCA-002");
        fixtures.enroll(batchA, classmate, "2027-01-10");
        fixtures.enroll(batchB, classmate, "2027-01-10");

        List<Map<String, Object>> own = list(api.get("/api/v1/me/enrollments", mine));

        assertEquals(1, own.size());
        assertEquals("PCA-001", own.get(0).get("studentNumber"));
        assertEquals(batchA, own.get(0).get("batchId"));
        assertFalse(api.get("/api/v1/me/enrollments", mine).body().contains("PCA-002"), "no classmate data");
        assertEquals(403, api.get("/api/v1/batches/" + batchA + "/enrollments", mine).statusCode(), "no roster access");
        assertEquals(403, api.get("/api/v1/students/" + classmate + "/enrollments", mine).statusCode(), "no other student's history");
        assertEquals(403, fixturesFor(mine).enroll(batchB, classmate, "2027-01-10").statusCode());
        assertEquals(403, api.get("/api/v1/me/enrollments", admin).statusCode(), "staff have no own enrollments");
        assertEquals(404, api.get("/api/v1/me/enrollments", tokenFor(academyId, Role.STUDENT)).statusCode());
    }

    @Test
    void aStudentInTwoBatchesSeesBothAndWithdrawnOnesAreLabelled() {
        String token = signedInStudent("PCA-001", batchA, batchB);
        String withdrawnId = list(api.get("/api/v1/me/enrollments", token)).stream()
                .filter(e -> batchB.equals(e.get("batchId"))).findFirst().orElseThrow().get("id").toString();
        api.put("/api/v1/enrollments/" + withdrawnId, updateBody("2027-01-10", "2027-02-01", 0), admin);

        List<Map<String, Object>> own = list(api.get("/api/v1/me/enrollments", token));

        assertEquals(List.of("ENROLLED", "WITHDRAWN"), own.stream().map(e -> e.get("status")).toList());
    }

    @Test
    void nonManagerStaffHaveNoEnrollmentAccessFromTheirRoleAlone() {
        String student = studentId("PCA-001");
        String id = (String) HttpApi.parse(fixtures.enroll(batchA, student, "2027-01-10")).get("id");

        for (Role role : List.of(Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER, Role.ANALYST)) {
            String token = tokenFor(academyId, role);
            int unassignedRoster = role == Role.ANALYST ? 403 : 404;
            assertEquals(unassignedRoster, api.get("/api/v1/batches/" + batchA + "/enrollments", token).statusCode(), role.name());
            assertEquals(403, fixturesFor(token).enroll(batchB, student, "2027-01-10").statusCode(), role.name());
            assertEquals(403, api.put("/api/v1/enrollments/" + id, updateBody("2027-01-10", "2027-02-01", 0), token).statusCode());
            assertEquals(403, api.get("/api/v1/students/" + student + "/enrollments", token).statusCode(), role.name());
            assertEquals(403, api.get("/api/v1/me/enrollments", token).statusCode(), role.name());
        }
        String outsider = tokenFor(academy("Other Academy"), Role.ACADEMY_ADMIN);
        assertEquals(404, api.get("/api/v1/batches/" + batchA + "/enrollments", outsider).statusCode());
        assertEquals(404, api.put("/api/v1/enrollments/" + id, updateBody("2027-01-10", "2027-02-01", 0), outsider).statusCode());
        assertEquals(404, api.get("/api/v1/students/" + student + "/enrollments", outsider).statusCode());
        assertEquals("ENROLLED", list(api.get("/api/v1/batches/" + batchA + "/enrollments", admin)).get(0).get("status"));
    }

    private AcademyFixtures fixturesFor(String token) {
        return new AcademyFixtures(api, token);
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        UUID otherAcademy = academy("Other Academy");
        String student = studentId("PCA-001");
        String insert = "INSERT INTO enrollments (id, academy_id, batch_id, student_id, enrolled_on, withdrawn_on) "
                + "VALUES (?, ?, ?::uuid, ?::uuid, ?, ?)";
        Date day = Date.valueOf("2027-01-10");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), otherAcademy,
                batchA, student, day, null), "batch and student must be in the enrollment's academy");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                batchA, student, day, Date.valueOf("2027-01-09")));
        assertEquals(1, jdbc.update(insert, UUID.randomUUID(), academyId, batchA, student, day, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                batchA, student, day, null), "one row per student per batch");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM batches WHERE id = ?::uuid", batchA));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM students WHERE id = ?::uuid", student));
    }
}
