package com.physicscube.luma.students;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class StudentHttpIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String admin;
    AcademyFixtures fixtures;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        fixtures = new AcademyFixtures(api, admin);
    }

    private static String updateBody(String name, boolean active, int version) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("fullName", name);
        fields.put("active", active);
        fields.put("version", version);
        return HttpApi.json(fields);
    }

    @SuppressWarnings("unchecked")
    private static List<String> numbers(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream().map(s -> (String) s.get("studentNumber")).toList();
    }

    private Map<String, Object> page(String query) {
        HttpResponse<String> response = api.get("/api/v1/students" + query, admin);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void adminCreatesAStudentWithAPendingStudentAccount() {
        HttpResponse<String> response = fixtures.postStudent(" pca-2027-001 ", "  Kavindi Perera ", "Kavindi.P@Example.INVALID");

        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> created = HttpApi.parse(response);
        assertEquals("PCA-2027-001", created.get("studentNumber"));
        assertEquals("Kavindi Perera", created.get("fullName"));
        assertEquals("kavindi.p@example.invalid", created.get("email"));
        assertEquals("PENDING_ACTIVATION", created.get("accountStatus"));
        assertEquals(true, created.get("active"));
        assertFalse(created.containsKey("academyId"));
        assertEquals("/api/v1/students/" + created.get("id"), response.headers().firstValue("Location").orElseThrow());
        UUID accountId = UUID.fromString((String) created.get("accountId"));
        assertEquals(List.of("STUDENT"), jdbc.queryForList("SELECT role FROM account_roles WHERE account_id = ?", String.class, accountId));
        assertNull(jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id = ?", String.class, accountId));
        assertEquals(created, HttpApi.parse(api.get("/api/v1/students/" + created.get("id"), admin)));

        activate(accountId);
        assertEquals("ACTIVE", HttpApi.parse(api.get("/api/v1/students/" + created.get("id"), admin)).get("accountStatus"));
    }

    @Test
    void duplicatesAreRejectedAndLeaveNoPartialRecords() {
        fixtures.student("PCA-001", "first@example.invalid");
        int accountsBefore = count("accounts");

        assertEquals(409, fixtures.postStudent("pca-001", "Again", "new@example.invalid").statusCode(), "same number");
        assertEquals(409, fixtures.postStudent("PCA-002", "Again", "FIRST@example.invalid").statusCode(), "same email");

        assertEquals(1, count("students"));
        assertEquals(accountsBefore, count("accounts"), "a rejected student must not leave an account behind");
        String otherAdmin = tokenFor(academy("Other Academy"), Role.ACADEMY_ADMIN);
        assertEquals(201, new AcademyFixtures(api, otherAdmin).postStudent("PCA-001", "Other", "other@example.invalid").statusCode(),
                "another academy may reuse the student number");
    }

    @Test
    void concurrentCreatesOfOneStudentNumberProduceExactlyOneStudentAndAccount() throws Exception {
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        int accountsBefore = count("accounts");
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, threads).<Callable<Integer>>mapToObj(i -> () -> {
                start.await();
                return fixtures.postStudent("RACE-1", "Racer " + i, "racer" + i + "@example.invalid").statusCode();
            }).toList())) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(threads - 1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(1, count("students"));
        assertEquals(accountsBefore + 1, count("accounts"));
    }

    @Test
    void invalidStudentDataIsRejected() {
        assertEquals(400, fixtures.postStudent("PCA-1", "Name", "not-an-email").statusCode());
        assertEquals(400, fixtures.postStudent("PCA 1", "Name", "a@example.invalid").statusCode());
        assertEquals(400, fixtures.postStudent("PCA-1", "  ", "a@example.invalid").statusCode());
        assertEquals(400, fixtures.postStudent("PCA-1", "N".repeat(201), "a@example.invalid").statusCode());
        assertEquals(400, api.post("/api/v1/students", "{}", admin).statusCode());
        assertEquals(0, count("students"));
    }

    @Test
    void listIsPagedByStudentNumberAndFilterable() {
        for (String number : List.of("PCA-005", "PCA-001", "PCA-004", "PCA-002", "PCA-003")) {
            fixtures.student(number, number.toLowerCase() + "@example.invalid");
        }
        String third = (String) HttpApi.parse(api.get("/api/v1/students?limit=200", admin)).get("nextAfter");
        assertNull(third);

        Map<String, Object> first = page("?limit=2");
        assertEquals(List.of("PCA-001", "PCA-002"), numbers(first));
        assertEquals("PCA-002", first.get("nextAfter"));
        Map<String, Object> second = page("?limit=2&after=pca-002");
        assertEquals(List.of("PCA-003", "PCA-004"), numbers(second));
        Map<String, Object> last = page("?limit=2&after=" + second.get("nextAfter"));
        assertEquals(List.of("PCA-005"), numbers(last));
        assertNull(last.get("nextAfter"));

        String id = (String) ((Map<?, ?>) ((List<?>) page("?limit=1").get("items")).get(0)).get("id");
        assertEquals(200, api.put("/api/v1/students/" + id, updateBody("Left", false, 0), admin).statusCode());
        assertEquals(List.of("PCA-001"), numbers(page("?active=false")));
        assertEquals(4, numbers(page("?active=true")).size());
        for (String bad : List.of("?limit=0", "?limit=201", "?limit=abc", "?active=maybe")) {
            assertEquals(400, api.get("/api/v1/students" + bad, admin).statusCode(), bad);
        }
    }

    @Test
    void updateChangesNameAndActiveButNeverTheStudentNumber() {
        Map<String, Object> student = fixtures.student("PCA-001", "s@example.invalid");
        String path = "/api/v1/students/" + student.get("id");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("studentNumber", "HIJACKED");
        fields.put("fullName", "Renamed");
        fields.put("active", false);
        fields.put("version", 0);

        HttpResponse<String> response = api.put(path, HttpApi.json(fields), admin);

        assertTrue(response.statusCode() == 200 || response.statusCode() == 400, response.body());
        assertEquals("PCA-001", HttpApi.parse(api.get(path, admin)).get("studentNumber"));
        if (response.statusCode() == 400) {
            response = api.put(path, updateBody("Renamed", false, 0), admin);
        }
        Map<String, Object> updated = HttpApi.parse(response);
        assertEquals("Renamed", updated.get("fullName"));
        assertEquals(false, updated.get("active"));
        assertEquals(1, ((Number) updated.get("version")).intValue());
        assertEquals(409, api.put(path, updateBody("Stale", true, 0), admin).statusCode());
        assertEquals(404, api.put("/api/v1/students/" + UUID.randomUUID(), updateBody("X", true, 0), admin).statusCode());
    }

    @Test
    void studentsOfAnotherAcademyAreInvisibleAndUntouchable() {
        Map<String, Object> ours = fixtures.student("PCA-001", "s@example.invalid");
        String outsider = tokenFor(academy("Other Academy"), Role.ACADEMY_OWNER);
        String path = "/api/v1/students/" + ours.get("id");

        assertEquals(404, api.get(path, outsider).statusCode());
        assertEquals(404, api.put(path, updateBody("Stolen", false, 0), outsider).statusCode());
        assertEquals(List.of(), numbers(HttpApi.parse(api.get("/api/v1/students", outsider))));
        assertEquals("Student PCA-001", HttpApi.parse(api.get(path, admin)).get("fullName"));
    }

    @Test
    void nonManagersCannotUseStaffStudentRoutes() {
        Map<String, Object> student = fixtures.student("PCA-001", "s@example.invalid");
        String path = "/api/v1/students/" + student.get("id");
        activate(UUID.fromString((String) student.get("accountId")));
        String self = login("s@example.invalid");

        for (Role role : List.of(Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER, Role.ANALYST, Role.STUDENT)) {
            String token = tokenFor(academyId, role);
            assertEquals(403, api.get("/api/v1/students", token).statusCode(), role.name());
            assertEquals(403, api.get(path, token).statusCode(), role.name());
            assertEquals(403, fixturesFor(token).postStudent("NEW", "New", "new" + role + "@example.invalid").statusCode(), role.name());
            assertEquals(403, api.put(path, updateBody("Changed", false, 0), token).statusCode(), role.name());
        }
        assertEquals(403, api.get(path, self).statusCode(), "a student cannot use the staff route even for their own record");
        assertEquals(401, api.get("/api/v1/students", null).statusCode());
        assertEquals(1, count("students"));
    }

    private AcademyFixtures fixturesFor(String token) {
        return new AcademyFixtures(api, token);
    }

    @Test
    void aStudentReadsOnlyTheirOwnRecord() {
        Map<String, Object> mine = fixtures.student("PCA-001", "me@example.invalid");
        fixtures.student("PCA-002", "classmate@example.invalid");
        activate(UUID.fromString((String) mine.get("accountId")));
        String token = login("me@example.invalid");

        HttpResponse<String> response = api.get("/api/v1/me/student", token);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(mine.get("id"), HttpApi.parse(response).get("id"));
        assertEquals("ACTIVE", HttpApi.parse(response).get("accountStatus"));
        assertEquals(404, api.get("/api/v1/me/student", tokenFor(academyId, Role.STUDENT)).statusCode(),
                "a student account without a student record has nothing to show");
        assertEquals(403, api.get("/api/v1/me/student", admin).statusCode(), "staff have no own student record");
        assertEquals(401, api.get("/api/v1/me/student", null).statusCode());
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        UUID otherAcademy = academy("Other Academy");
        UUID localAccount = accounts.register(academyId, "local@example.invalid", java.util.Set.of(Role.STUDENT)).id();
        UUID foreignAccount = accounts.register(otherAcademy, "foreign@example.invalid", java.util.Set.of(Role.STUDENT)).id();
        String insert = "INSERT INTO students (id, academy_id, account_id, student_number, full_name) VALUES (?, ?, ?, ?, ?)";

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                foreignAccount, "PCA-1", "N"), "a student's account must be in the student's academy");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                localAccount, "lower", "N"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                localAccount, "PCA-1", " "));
        assertEquals(1, jdbc.update(insert, UUID.randomUUID(), academyId, localAccount, "PCA-1", "N"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                localAccount, "PCA-2", "N"), "one student record per account");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM accounts WHERE id = ?", localAccount),
                "an account with a student record cannot be removed");
    }
}
