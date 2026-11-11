package com.physicscube.luma.courses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
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

class CourseHttpIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String admin;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
    }

    private static String body(String code, String title) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", code);
        fields.put("title", title);
        return HttpApi.json(fields);
    }

    private static String body(String code, String title, boolean active, Object version) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", code);
        fields.put("title", title);
        fields.put("active", active);
        fields.put("version", version);
        return HttpApi.json(fields);
    }

    private Map<String, Object> create(String token, String code, String title) {
        HttpResponse<String> response = api.post("/api/v1/courses", body(code, title), token);
        assertEquals(201, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private <T> List<T> race(int threads, Callable<T> action) throws Exception {
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<T> results = new ArrayList<>();
            for (Future<T> future : pool.invokeAll(IntStream.range(0, threads).<Callable<T>>mapToObj(i -> () -> {
                start.await();
                return action.call();
            }).toList())) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void adminCreatesAndReadsACourse() {
        HttpResponse<String> response = api.post("/api/v1/courses", body("  al-phy-2027 ", "  A/L Physics 2027  "), admin);

        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> created = HttpApi.parse(response);
        assertEquals("AL-PHY-2027", created.get("code"), "codes are trimmed and upper-cased");
        assertEquals("A/L Physics 2027", created.get("title"));
        assertEquals(true, created.get("active"));
        assertEquals(0, ((Number) created.get("version")).intValue());
        assertTrue(Instant.parse((String) created.get("createdAt")).isBefore(Instant.now().plusSeconds(5)));
        assertFalse(created.containsKey("academyId"), "the academy is implied by the caller, never echoed or accepted");
        assertEquals("/api/v1/courses/" + created.get("id"), response.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> fetched = api.get("/api/v1/courses/" + created.get("id"), admin);
        assertEquals(200, fetched.statusCode());
        assertEquals(created, HttpApi.parse(fetched));
        assertEquals(academyId, jdbc.queryForObject("SELECT academy_id FROM courses WHERE id = ?::uuid", UUID.class, created.get("id")));
    }

    @Test
    void ownerMayManageCoursesToo() {
        String owner = tokenFor(academyId, Role.ACADEMY_OWNER);
        assertEquals("OWNER-1", create(owner, "owner-1", "Owner course").get("code"));
        assertEquals(200, api.get("/api/v1/courses", owner).statusCode());
    }

    @Test
    void listIsSortedByCodeAndCanFilterByActive() {
        create(admin, "PHY-B", "Second");
        Map<String, Object> first = create(admin, "PHY-A", "First");
        api.put("/api/v1/courses/" + first.get("id"), body("PHY-A", "First", false, 0), admin);

        assertEquals(List.of("PHY-A", "PHY-B"), codes(api.get("/api/v1/courses", admin)));
        assertEquals(List.of("PHY-B"), codes(api.get("/api/v1/courses?active=true", admin)));
        assertEquals(List.of("PHY-A"), codes(api.get("/api/v1/courses?active=false", admin)));
        assertEquals(400, api.get("/api/v1/courses?active=maybe", admin).statusCode());
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(item -> (String) ((Map<String, Object>) item).get("code")).toList();
    }

    @Test
    void invalidCourseDataIsRejected() {
        for (String invalid : List.of(body("", "Title"), body("has space", "Title"), body("-leading", "Title"),
                body("X".repeat(61), "Title"), body("OK", ""), body("OK", "   "), body("OK", "T".repeat(201)),
                "{}", "{\"code\":\"OK\"}", "not json")) {
            assertEquals(400, api.post("/api/v1/courses", invalid, admin).statusCode(), invalid);
        }
        Map<String, Object> course = create(admin, "PHY", "Physics");
        String path = "/api/v1/courses/" + course.get("id");
        assertEquals(400, api.put(path, body("PHY", "Physics"), admin).statusCode(), "active and version are required");
        assertEquals(400, api.put(path, body("PHY", "Physics", true, -1), admin).statusCode());
        assertEquals(400, api.put(path, body("PHY", "Physics", true, "\"zero\"".replace("\"", "")), admin).statusCode());
        assertEquals(400, api.get("/api/v1/courses/not-a-uuid", admin).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class));
    }

    @Test
    void courseCodeIsUniqueWithinAnAcademyRegardlessOfCase() {
        create(admin, "PHY-2027", "Physics");

        HttpResponse<String> duplicate = api.post("/api/v1/courses", body("phy-2027", "Another"), admin);

        assertEquals(409, duplicate.statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class));
        String otherAdmin = tokenFor(academy("Other Academy"), Role.ACADEMY_ADMIN);
        assertEquals("PHY-2027", create(otherAdmin, "PHY-2027", "Their physics").get("code"),
                "another academy may use the same code");
    }

    @Test
    void concurrentCreatesOfOneCodeProduceExactlyOneCourse() throws Exception {
        List<Integer> statuses = race(8, () -> api.post("/api/v1/courses", body("RACE", "Race"), admin).statusCode());

        assertEquals(1, statuses.stream().filter(status -> status == 201).count(), statuses.toString());
        assertEquals(7, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class));
    }

    @Test
    void updateChangesFieldsAndAdvancesTheVersion() {
        Map<String, Object> course = create(admin, "PHY", "Physics");
        String path = "/api/v1/courses/" + course.get("id");

        HttpResponse<String> response = api.put(path, body("phy-rev", "Physics revision", false, 0), admin);

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> updated = HttpApi.parse(response);
        assertEquals("PHY-REV", updated.get("code"));
        assertEquals("Physics revision", updated.get("title"));
        assertEquals(false, updated.get("active"));
        assertEquals(1, ((Number) updated.get("version")).intValue());
        assertEquals(course.get("createdAt"), updated.get("createdAt"));
        assertEquals(updated, HttpApi.parse(api.get(path, admin)));
    }

    @Test
    void staleUpdatesAreRejected() throws Exception {
        Map<String, Object> course = create(admin, "PHY", "Physics");
        String path = "/api/v1/courses/" + course.get("id");
        assertEquals(200, api.put(path, body("PHY", "First edit", true, 0), admin).statusCode());

        assertEquals(409, api.put(path, body("PHY", "Lost edit", true, 0), admin).statusCode());
        assertEquals("First edit", HttpApi.parse(api.get(path, admin)).get("title"));

        List<Integer> statuses = race(8, () -> api.put(path, body("PHY", "Racing edit", true, 1), admin).statusCode());
        assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        assertEquals(7, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        assertEquals(2, ((Number) HttpApi.parse(api.get(path, admin)).get("version")).intValue());
    }

    @Test
    void updateCannotTakeAnotherCoursesCode() {
        create(admin, "PHY-A", "A");
        Map<String, Object> second = create(admin, "PHY-B", "B");

        assertEquals(409, api.put("/api/v1/courses/" + second.get("id"), body("phy-a", "B", true, 0), admin).statusCode());
        assertEquals("PHY-B", HttpApi.parse(api.get("/api/v1/courses/" + second.get("id"), admin)).get("code"));
    }

    @Test
    void coursesOfAnotherAcademyAreInvisibleAndUntouchable() {
        Map<String, Object> ours = create(admin, "PHY", "Physics");
        String outsider = tokenFor(academy("Other Academy"), Role.ACADEMY_OWNER);
        String path = "/api/v1/courses/" + ours.get("id");

        HttpResponse<String> read = api.get(path, outsider);
        HttpResponse<String> missing = api.get("/api/v1/courses/" + UUID.randomUUID(), outsider);

        assertEquals(404, read.statusCode());
        assertEquals(404, missing.statusCode());
        assertEquals(strip(missing.body()), strip(read.body()), "indistinguishable from a course that does not exist");
        assertEquals(404, api.put(path, body("STOLEN", "Stolen", false, 0), outsider).statusCode());
        assertEquals(List.of(), codes(api.get("/api/v1/courses", outsider)));
        assertEquals("Physics", HttpApi.parse(api.get(path, admin)).get("title"));
        assertEquals(0, ((Number) HttpApi.parse(api.get(path, admin)).get("version")).intValue());
    }

    private static String strip(String problem) {
        return problem.replaceAll("\"instance\":\"[^\"]*\"", "");
    }

    @Test
    void anAcademyIdInTheRequestBodyIsIgnored() {
        UUID other = academy("Other Academy");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", "PHY");
        fields.put("title", "Physics");
        fields.put("academyId", other.toString());

        HttpResponse<String> response = api.post("/api/v1/courses", HttpApi.json(fields), admin);

        assertTrue(response.statusCode() == 201 || response.statusCode() == 400, response.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM courses WHERE academy_id = ?", Integer.class, other));
    }

    @Test
    void nonManagerRolesAreForbiddenOnEveryCourseRoute() {
        Map<String, Object> course = create(admin, "PHY", "Physics");
        String path = "/api/v1/courses/" + course.get("id");

        for (Role role : List.of(Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER, Role.ANALYST, Role.STUDENT)) {
            String token = tokenFor(academyId, role);
            assertEquals(403, api.get("/api/v1/courses", token).statusCode(), role.name());
            assertEquals(403, api.get(path, token).statusCode(), role.name());
            assertEquals(403, api.post("/api/v1/courses", body("NEW", "New"), token).statusCode(), role.name());
            assertEquals(403, api.put(path, body("PHY", "Changed", false, 0), token).statusCode(), role.name());
        }
        assertEquals(401, api.get("/api/v1/courses", null).statusCode());
        assertEquals(401, api.post("/api/v1/courses", body("NEW", "New")).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class));
        assertEquals("Physics", HttpApi.parse(api.get(path, admin)).get("title"));
    }

    @Test
    void losingTheAdminRoleTakesEffectOnTheNextRequest() {
        UUID accountId = accountIdOf("demoted@example.invalid", academyId, Role.ACADEMY_ADMIN, Role.INSTRUCTOR);
        String token = login("demoted@example.invalid");
        assertEquals(200, api.get("/api/v1/courses", token).statusCode());

        jdbc.update("DELETE FROM account_roles WHERE account_id = ? AND role = 'ACADEMY_ADMIN'", accountId);

        assertEquals(403, api.get("/api/v1/courses", token).statusCode());
        assertEquals(403, api.post("/api/v1/courses", body("NEW", "New"), token).statusCode());
    }

    @Test
    void coursesCannotBeDeleted() {
        Map<String, Object> course = create(admin, "PHY", "Physics");

        int status = api.delete("/api/v1/courses/" + course.get("id"), admin).statusCode();

        assertTrue(status == 405 || status == 403, "DELETE returned " + status);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM courses", Integer.class));
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        String insert = "INSERT INTO courses (id, academy_id, code, title) VALUES (?, ?, ?, ?)";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId, "lower", "T"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId, " PAD ", "T"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId, "OK", "  "));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), "OK", "T"));
        assertEquals(1, jdbc.update(insert, UUID.randomUUID(), academyId, "OK", "T"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId, "OK", "T"));
        assertNotEquals(0, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success", Integer.class));
    }
}
