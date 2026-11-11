package com.physicscube.luma.batches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Batch-scoped access: an instructor assigned to Batch A must never reach Batch B. */
class StaffAssignmentHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final AtomicInteger PEOPLE = new AtomicInteger();

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
        for (String batch : List.of(batchA, batchB)) {
            String number = "PCA-" + batch.substring(0, 8).toUpperCase();
            Map<String, Object> student = fixtures.student(number, number.toLowerCase() + "@example.invalid");
            assertEquals(201, fixtures.enroll(batch, (String) student.get("id"), "2027-01-10").statusCode());
        }
    }

    /** A staff member's email, registered and activated with the roles. */
    private String staff(UUID academy, Role... roles) {
        String email = "staff" + PEOPLE.incrementAndGet() + "@example.invalid";
        accountIdOf(email, academy, roles);
        return email;
    }

    private HttpResponse<String> assign(String token, String batch, String email, String role) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("email", email);
        fields.put("role", role);
        return api.post("/api/v1/batches/" + batch + "/assignments", HttpApi.json(fields), token);
    }

    private String assigned(String batch, String email, String role) {
        HttpResponse<String> response = assign(admin, batch, email, role);
        assertEquals(201, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<String> field(HttpResponse<String> response, String name) {
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(item -> (String) ((Map<String, Object>) item).get(name)).toList();
    }

    @Test
    void anInstructorAssignedToBatchASeesBatchAAndNeverBatchB() {
        String email = staff(academyId, Role.INSTRUCTOR);
        assigned(batchA, email, "INSTRUCTOR");
        String instructor = login(email);

        assertEquals(List.of(batchA), field(api.get("/api/v1/batches", instructor), "id"));
        assertEquals(List.of(batchA), field(api.get("/api/v1/batches?courseId=" + physics, instructor), "id"));
        assertEquals(200, api.get("/api/v1/batches/" + batchA, instructor).statusCode());
        assertEquals(1, field(api.get("/api/v1/batches/" + batchA + "/enrollments", instructor), "studentNumber").size());

        HttpResponse<String> other = api.get("/api/v1/batches/" + batchB, instructor);
        HttpResponse<String> missing = api.get("/api/v1/batches/" + UUID.randomUUID(), instructor);
        assertEquals(404, other.statusCode());
        assertEquals(missing.body().replaceAll("\"instance\":\"[^\"]*\"", ""), other.body().replaceAll("\"instance\":\"[^\"]*\"", ""),
                "an unassigned batch looks exactly like one that does not exist");
        assertEquals(404, api.get("/api/v1/batches/" + batchB + "/enrollments", instructor).statusCode());
        assertFalse(api.get("/api/v1/batches", instructor).body().contains(batchB));
    }

    @Test
    void assignedStaffCanOnlyReadNeverManage() {
        String email = staff(academyId, Role.INSTRUCTOR);
        assigned(batchA, email, "INSTRUCTOR");
        String instructor = login(email);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("code", "A");
        update.put("name", "Renamed");
        update.put("startsOn", "2027-01-10");
        update.put("active", false);
        update.put("version", 0);

        assertEquals(403, api.put("/api/v1/batches/" + batchA, HttpApi.json(update), instructor).statusCode());
        assertEquals(403, new AcademyFixtures(api, instructor).enroll(batchA, UUID.randomUUID().toString(), "2027-01-10").statusCode());
        assertEquals(403, api.get("/api/v1/batches/" + batchA + "/assignments", instructor).statusCode());
        assertEquals(403, assign(instructor, batchA, email, "INSTRUCTOR").statusCode(), "staff cannot assign themselves");
        assertEquals(403, assign(instructor, batchB, email, "INSTRUCTOR").statusCode());
        assertEquals(403, api.get("/api/v1/courses", instructor).statusCode());
        assertEquals(403, api.get("/api/v1/students", instructor).statusCode());
        assertEquals("Batch A", HttpApi.parse(api.get("/api/v1/batches/" + batchA, admin)).get("name"));
    }

    @Test
    void attendanceOfficersAreScopedTheSameWay() {
        String email = staff(academyId, Role.ATTENDANCE_OFFICER);
        assigned(batchB, email, "ATTENDANCE_OFFICER");
        String officer = login(email);

        assertEquals(List.of(batchB), field(api.get("/api/v1/batches", officer), "id"));
        assertEquals(200, api.get("/api/v1/batches/" + batchB + "/enrollments", officer).statusCode());
        assertEquals(404, api.get("/api/v1/batches/" + batchA + "/enrollments", officer).statusCode());
        assertEquals(List.of("ATTENDANCE_OFFICER"), field(api.get("/api/v1/me/assignments", officer), "role"));
    }

    @Test
    void accessFollowsTheRoleTheAccountHoldsNow() {
        String email = staff(academyId, Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER);
        assigned(batchA, email, "INSTRUCTOR");
        assigned(batchB, email, "ATTENDANCE_OFFICER");
        String token = login(email);
        assertEquals(2, field(api.get("/api/v1/batches", token), "id").size());
        UUID accountId = accounts.findByEmail(email).orElseThrow().id();

        jdbc.update("DELETE FROM account_roles WHERE account_id = ? AND role = 'INSTRUCTOR'", accountId);

        assertEquals(List.of(batchB), field(api.get("/api/v1/batches", token), "id"),
                "an assignment in a role no longer held grants nothing, without signing in again");
        assertEquals(404, api.get("/api/v1/batches/" + batchA + "/enrollments", token).statusCode());

        jdbc.update("DELETE FROM account_roles WHERE account_id = ? AND role = 'ATTENDANCE_OFFICER'", accountId);
        jdbc.update("INSERT INTO account_roles (account_id, role) VALUES (?, 'ANALYST')", accountId);
        assertEquals(403, api.get("/api/v1/batches", token).statusCode());
    }

    @Test
    void unassigningRevokesAccessOnTheNextRequest() {
        String email = staff(academyId, Role.INSTRUCTOR);
        String assignment = assigned(batchA, email, "INSTRUCTOR");
        String instructor = login(email);
        assertEquals(200, api.get("/api/v1/batches/" + batchA + "/enrollments", instructor).statusCode());

        assertEquals(403, api.delete("/api/v1/assignments/" + assignment, instructor).statusCode(), "staff cannot remove assignments");
        assertEquals(204, api.delete("/api/v1/assignments/" + assignment, admin).statusCode());

        assertEquals(404, api.get("/api/v1/batches/" + batchA + "/enrollments", instructor).statusCode());
        assertEquals(List.of(), field(api.get("/api/v1/batches", instructor), "id"));
        assertEquals(List.of(), field(api.get("/api/v1/me/assignments", instructor), "id"));
        assertEquals(404, api.delete("/api/v1/assignments/" + assignment, admin).statusCode());
    }

    @Test
    void adminListsAndManagesAssignments() {
        String email = staff(academyId, Role.INSTRUCTOR);
        HttpResponse<String> created = assign(admin, batchA, " " + email.toUpperCase() + " ", "INSTRUCTOR");

        assertEquals(201, created.statusCode(), created.body());
        Map<String, Object> body = HttpApi.parse(created);
        assertEquals(email, body.get("email"));
        assertEquals("A", body.get("batchCode"));
        assertEquals(physics, body.get("courseId"));
        assertEquals("/api/v1/assignments/" + body.get("id"), created.headers().firstValue("Location").orElseThrow());
        assertEquals(List.of(email), field(api.get("/api/v1/batches/" + batchA + "/assignments", admin), "email"));
        assertEquals(List.of(), field(api.get("/api/v1/batches/" + batchB + "/assignments", admin), "email"));
        assertEquals(409, assign(admin, batchA, email, "INSTRUCTOR").statusCode(), "duplicate");
    }

    @Test
    void onlySuitableAccountsOfThisAcademyCanBeAssigned() {
        String student = (String) fixtures.student("PCA-ZZZ", "learner@example.invalid").get("email");
        String analyst = staff(academyId, Role.ANALYST);
        String disabled = staff(academyId, Role.INSTRUCTOR);
        accounts.disable(accounts.findByEmail(disabled).orElseThrow().id());
        String foreign = staff(academy("Other Academy"), Role.INSTRUCTOR);
        String instructor = staff(academyId, Role.INSTRUCTOR);

        assertEquals(409, assign(admin, batchA, student, "INSTRUCTOR").statusCode(), "a student is not an instructor");
        assertEquals(409, assign(admin, batchA, analyst, "INSTRUCTOR").statusCode());
        assertEquals(409, assign(admin, batchA, instructor, "ATTENDANCE_OFFICER").statusCode(), "role must match");
        assertEquals(409, assign(admin, batchA, disabled, "INSTRUCTOR").statusCode());
        assertEquals(400, assign(admin, batchA, instructor, "ANALYST").statusCode());
        assertEquals(400, assign(admin, batchA, instructor, "ACADEMY_ADMIN").statusCode());
        assertEquals(400, api.post("/api/v1/batches/" + batchA + "/assignments", "{}", admin).statusCode());
        HttpResponse<String> foreignResponse = assign(admin, batchA, foreign, "INSTRUCTOR");
        HttpResponse<String> unknownResponse = assign(admin, batchA, "nobody@example.invalid", "INSTRUCTOR");
        assertEquals(404, foreignResponse.statusCode());
        assertEquals(unknownResponse.body().replaceAll("\"instance\":\"[^\"]*\"", ""),
                foreignResponse.body().replaceAll("\"instance\":\"[^\"]*\"", ""), "another academy's emails are not revealed");
        assertEquals(404, assign(admin, batchA, "not-an-email", "INSTRUCTOR").statusCode());
        assertEquals(404, assign(admin, UUID.randomUUID().toString(), instructor, "INSTRUCTOR").statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM staff_assignments", Integer.class));
    }

    @Test
    void concurrentAssignmentsOfOnePersonProduceExactlyOneRow() throws Exception {
        String email = staff(academyId, Role.INSTRUCTOR);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, threads).<Callable<Integer>>mapToObj(i -> () -> {
                start.await();
                return assign(admin, batchA, email, "INSTRUCTOR").statusCode();
            }).toList())) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(threads - 1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
    }

    @Test
    void otherAcademiesCannotSeeOrManageAssignments() {
        String email = staff(academyId, Role.INSTRUCTOR);
        String assignment = assigned(batchA, email, "INSTRUCTOR");
        UUID other = academy("Other Academy");
        String outsiderAdmin = tokenFor(other, Role.ACADEMY_OWNER);
        String outsiderInstructor = tokenFor(other, Role.INSTRUCTOR);

        assertEquals(404, api.get("/api/v1/batches/" + batchA + "/assignments", outsiderAdmin).statusCode());
        assertEquals(404, assign(outsiderAdmin, batchA, email, "INSTRUCTOR").statusCode());
        assertEquals(404, api.delete("/api/v1/assignments/" + assignment, outsiderAdmin).statusCode());
        assertEquals(404, api.get("/api/v1/batches/" + batchA, outsiderInstructor).statusCode());
        assertEquals(List.of(), field(api.get("/api/v1/batches", outsiderInstructor), "id"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM staff_assignments", Integer.class));
    }

    @Test
    void studentsAnalystsAndUnauthenticatedCallersGetNoBatchOrAssignmentAccess() {
        String studentEmail = (String) fixtures.student("PCA-ZZZ", "learner@example.invalid").get("email");
        activate(accounts.findByEmail(studentEmail).orElseThrow().id());
        for (String token : List.of(login(studentEmail), tokenFor(academyId, Role.ANALYST))) {
            assertEquals(403, api.get("/api/v1/batches", token).statusCode());
            assertEquals(403, api.get("/api/v1/batches/" + batchA, token).statusCode());
            assertEquals(403, api.get("/api/v1/batches/" + batchA + "/enrollments", token).statusCode());
            assertEquals(403, api.get("/api/v1/me/assignments", token).statusCode());
        }
        assertEquals(403, api.get("/api/v1/me/assignments", admin).statusCode(), "managers have no assignments of their own");
        assertEquals(401, api.get("/api/v1/me/assignments", null).statusCode());
        assertEquals(401, api.get("/api/v1/batches/" + batchA + "/enrollments", null).statusCode());
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        UUID other = academy("Other Academy");
        UUID local = accounts.findByEmail(staff(academyId, Role.INSTRUCTOR)).orElseThrow().id();
        UUID foreign = accounts.findByEmail(staff(other, Role.INSTRUCTOR)).orElseThrow().id();
        String insert = "INSERT INTO staff_assignments (id, academy_id, account_id, batch_id, role) VALUES (?, ?, ?, ?::uuid, ?)";

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId, foreign,
                batchA, "INSTRUCTOR"), "the account must be in the assignment's academy");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), other, foreign,
                batchA, "INSTRUCTOR"), "the batch must be in the assignment's academy");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId, local,
                batchA, "ACADEMY_ADMIN"));
        assertEquals(1, jdbc.update(insert, UUID.randomUUID(), academyId, local, batchA, "INSTRUCTOR"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId, local,
                batchA, "INSTRUCTOR"));
        assertEquals(1, jdbc.update(insert, UUID.randomUUID(), academyId, local, batchA, "ATTENDANCE_OFFICER"),
                "the same person may hold both roles in one batch");
        jdbc.update("DELETE FROM refresh_sessions WHERE account_id = ?", local);
        jdbc.update("DELETE FROM accounts WHERE id = ?", local);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM staff_assignments", Integer.class), "assignments go with the account");
    }
}
