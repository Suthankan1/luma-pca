package com.physicscube.luma.batches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
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

class BatchHttpIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String admin;
    String courseId;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        courseId = course(admin, "PHY-2027");
    }

    private String course(String token, String code) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", code);
        fields.put("title", "Course " + code);
        HttpResponse<String> response = api.post("/api/v1/courses", HttpApi.json(fields), token);
        assertEquals(201, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("id");
    }

    private static Map<String, Object> fields(String course, String code, String name, String startsOn, String endsOn) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (course != null) {
            fields.put("courseId", course);
        }
        fields.put("code", code);
        fields.put("name", name);
        fields.put("startsOn", startsOn);
        fields.put("endsOn", endsOn);
        return fields;
    }

    private static String updateBody(String code, String name, String startsOn, String endsOn, boolean active, int version) {
        Map<String, Object> fields = fields(null, code, name, startsOn, endsOn);
        fields.put("active", active);
        fields.put("version", version);
        return HttpApi.json(fields);
    }

    private HttpResponse<String> post(String token, String course, String code, String startsOn, String endsOn) {
        return api.post("/api/v1/batches", HttpApi.json(fields(course, code, "Batch " + code, startsOn, endsOn)), token);
    }

    private Map<String, Object> create(String course, String code) {
        HttpResponse<String> response = post(admin, course, code, "2027-01-10", "2027-12-15");
        assertEquals(201, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(item -> (String) ((Map<String, Object>) item).get("code")).toList();
    }

    @Test
    void adminCreatesAndReadsABatch() {
        HttpResponse<String> response = post(admin, courseId, " mon-eve ", "2027-01-10", null);

        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> created = HttpApi.parse(response);
        assertEquals("MON-EVE", created.get("code"));
        assertEquals(courseId, created.get("courseId"));
        assertEquals("2027-01-10", created.get("startsOn"));
        assertEquals(null, created.get("endsOn"));
        assertEquals(true, created.get("active"));
        assertFalse(created.containsKey("academyId"));
        assertEquals("/api/v1/batches/" + created.get("id"), response.headers().firstValue("Location").orElseThrow());
        assertEquals(created, HttpApi.parse(api.get("/api/v1/batches/" + created.get("id"), admin)));
        assertEquals(academyId, jdbc.queryForObject("SELECT academy_id FROM batches WHERE id = ?::uuid", UUID.class, created.get("id")));
    }

    @Test
    void aCourseHasManyBatchesAndListsCanBeFilteredByCourse() {
        String otherCourse = course(admin, "CHEM-2027");
        assertEquals(201, post(admin, courseId, "A", "2027-01-10", null).statusCode());
        assertEquals(201, post(admin, courseId, "B", "2027-03-01", null).statusCode());
        assertEquals(201, post(admin, otherCourse, "A", "2027-02-01", null).statusCode(), "codes repeat across courses");

        assertEquals(List.of("B", "A", "A"), codes(api.get("/api/v1/batches", admin)), "newest start first");
        assertEquals(List.of("B", "A"), codes(api.get("/api/v1/batches?courseId=" + courseId, admin)));
        assertEquals(List.of("A"), codes(api.get("/api/v1/batches?courseId=" + otherCourse, admin)));
        assertEquals(404, api.get("/api/v1/batches?courseId=" + UUID.randomUUID(), admin).statusCode());
        assertEquals(400, api.get("/api/v1/batches?courseId=nope", admin).statusCode());
    }

    @Test
    void invalidBatchDataIsRejected() {
        assertEquals(400, post(admin, courseId, "A", "2027-06-01", "2027-05-31").statusCode(), "ends before it starts");
        assertEquals(400, post(admin, courseId, "A", "not-a-date", null).statusCode());
        assertEquals(400, post(admin, courseId, "A", "2027-02-30", null).statusCode(), "impossible date");
        assertEquals(400, post(admin, courseId, "has space", "2027-01-10", null).statusCode());
        assertEquals(400, post(admin, null, "A", "2027-01-10", null).statusCode(), "course is required");
        assertEquals(400, post(admin, "not-a-uuid", "A", "2027-01-10", null).statusCode());
        assertEquals(400, api.post("/api/v1/batches", HttpApi.json(fields(courseId, "A", " ", "2027-01-10", null)), admin).statusCode());
        assertEquals(400, api.post("/api/v1/batches", HttpApi.json(fields(courseId, "A", "Name", null, null)), admin).statusCode());
        assertEquals(201, post(admin, courseId, "A", "2027-06-01", "2027-06-01").statusCode(), "a one-day batch is valid");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM batches", Integer.class));
    }

    @Test
    void batchCodeIsUniqueWithinItsCourse() throws Exception {
        create(courseId, "MON");
        assertEquals(409, post(admin, courseId, "mon", "2028-01-01", null).statusCode());

        CyclicBarrier start = new CyclicBarrier(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, 8).<Callable<Integer>>mapToObj(i -> () -> {
                start.await();
                return post(admin, courseId, "RACE", "2027-01-10", null).statusCode();
            }).toList())) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, statuses.stream().filter(status -> status == 201).count(), statuses.toString());
        assertEquals(7, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM batches", Integer.class));
    }

    @Test
    void batchesCannotBeAddedToAnInactiveOrForeignCourse() {
        Map<String, Object> deactivate = new LinkedHashMap<>();
        deactivate.put("code", "PHY-2027");
        deactivate.put("title", "Course PHY-2027");
        deactivate.put("active", false);
        deactivate.put("version", 0);
        assertEquals(200, api.put("/api/v1/courses/" + courseId, HttpApi.json(deactivate), admin).statusCode());
        assertEquals(409, post(admin, courseId, "A", "2027-01-10", null).statusCode());

        UUID otherAcademy = academy("Other Academy");
        String otherAdmin = tokenFor(otherAcademy, Role.STAFF);
        String theirCourse = course(otherAdmin, "THEIRS");
        HttpResponse<String> foreign = post(admin, theirCourse, "A", "2027-01-10", null);
        assertEquals(404, foreign.statusCode(), "another academy's course must look nonexistent");
        assertEquals(404, post(admin, UUID.randomUUID().toString(), "A", "2027-01-10", null).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM batches", Integer.class));
    }

    @Test
    void updateChangesFieldsButNeverTheCourse() {
        Map<String, Object> batch = create(courseId, "MON");
        String otherCourse = course(admin, "CHEM-2027");
        String path = "/api/v1/batches/" + batch.get("id");
        Map<String, Object> fields = fields(otherCourse, "tue", "Tuesday", "2027-02-01", null);
        fields.put("active", false);
        fields.put("version", 0);

        HttpResponse<String> response = api.put(path, HttpApi.json(fields), admin);

        assertTrue(response.statusCode() == 200 || response.statusCode() == 400, response.body());
        assertEquals(courseId, HttpApi.parse(api.get(path, admin)).get("courseId"), "a batch cannot be moved to another course");
        if (response.statusCode() == 400) {
            response = api.put(path, updateBody("tue", "Tuesday", "2027-02-01", null, false, 0), admin);
        }
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> updated = HttpApi.parse(response);
        assertEquals("TUE", updated.get("code"));
        assertEquals("Tuesday", updated.get("name"));
        assertEquals("2027-02-01", updated.get("startsOn"));
        assertEquals(null, updated.get("endsOn"));
        assertEquals(false, updated.get("active"));
        assertEquals(1, ((Number) updated.get("version")).intValue());
    }

    @Test
    void staleOrInvalidUpdatesAreRejected() {
        Map<String, Object> batch = create(courseId, "MON");
        create(courseId, "TUE");
        String path = "/api/v1/batches/" + batch.get("id");
        assertEquals(200, api.put(path, updateBody("MON", "First edit", "2027-01-10", null, true, 0), admin).statusCode());

        assertEquals(409, api.put(path, updateBody("MON", "Lost edit", "2027-01-10", null, true, 0), admin).statusCode());
        assertEquals(409, api.put(path, updateBody("tue", "Clash", "2027-01-10", null, true, 1), admin).statusCode());
        assertEquals(400, api.put(path, updateBody("MON", "Bad dates", "2027-05-01", "2027-04-01", true, 1), admin).statusCode());
        assertEquals(404, api.put("/api/v1/batches/" + UUID.randomUUID(), updateBody("X", "X", "2027-01-10", null, true, 0), admin).statusCode());
        assertEquals("First edit", HttpApi.parse(api.get(path, admin)).get("name"));
    }

    @Test
    void batchesOfAnotherAcademyAreInvisibleAndUntouchable() {
        Map<String, Object> ours = create(courseId, "MON");
        String outsider = tokenFor(academy("Other Academy"), Role.STAFF);
        String path = "/api/v1/batches/" + ours.get("id");

        assertEquals(404, api.get(path, outsider).statusCode());
        assertEquals(404, api.put(path, updateBody("STOLEN", "Stolen", "2027-01-10", null, false, 0), outsider).statusCode());
        assertEquals(List.of(), codes(api.get("/api/v1/batches", outsider)));
        assertEquals(404, api.get("/api/v1/batches?courseId=" + courseId, outsider).statusCode());
        assertEquals("Batch MON", HttpApi.parse(api.get(path, admin)).get("name"));
    }

    @Test
    void anyStaffMemberManagesBatchesAndStudentsReachNone() {
        Map<String, Object> batch = create(courseId, "MON");
        String path = "/api/v1/batches/" + batch.get("id");

        String colleague = tokenFor(academyId, Role.STAFF);
        assertEquals(List.of("MON"), codes(api.get("/api/v1/batches", colleague)), "no batch assignments any more");
        assertEquals(200, api.get(path, colleague).statusCode());
        assertEquals(201, post(colleague, courseId, "TUE", "2027-01-10", null).statusCode());

        String student = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, api.get("/api/v1/batches", student).statusCode());
        assertEquals(403, api.get(path, student).statusCode());
        assertEquals(403, post(student, courseId, "NEW", "2027-01-10", null).statusCode());
        assertEquals(403, api.put(path, updateBody("MON", "Changed", "2027-01-10", null, false, 0), student).statusCode());
        assertEquals(401, api.get("/api/v1/batches", null).statusCode());
        int deleted = api.delete(path, admin).statusCode();
        assertTrue(deleted == 405 || deleted == 403, "DELETE returned " + deleted);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM batches", Integer.class));
        assertEquals("Batch MON", HttpApi.parse(api.get(path, admin)).get("name"));
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        UUID otherAcademy = academy("Other Academy");
        String insert = "INSERT INTO batches (id, academy_id, course_id, code, name, starts_on, ends_on) VALUES (?, ?, ?::uuid, ?, ?, ?, ?)";
        Date start = Date.valueOf("2027-01-10");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), otherAcademy,
                courseId, "A", "N", start, null), "a batch must be in its course's academy");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                courseId, "lower", "N", start, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                courseId, "A", "N", start, Date.valueOf("2027-01-09")));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                courseId, "A", " ", start, null));
        assertEquals(1, jdbc.update(insert, UUID.randomUUID(), academyId, courseId, "A", "N", start, null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, UUID.randomUUID(), academyId,
                courseId, "A", "N", start, null), "duplicate code in a course");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM courses WHERE id = ?::uuid", courseId),
                "a course with batches cannot be removed");
    }
}
