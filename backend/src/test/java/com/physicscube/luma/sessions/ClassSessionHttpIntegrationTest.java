package com.physicscube.luma.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ClassSessionHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final String START = "2027-03-01T08:30:00Z";
    private static final String END = "2027-03-01T10:30:00Z";

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String physics;
    String batchA;
    String batchB;
    String batchC;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        fixtures = new AcademyFixtures(api, admin);
        physics = fixtures.course("PHY-2027");
        batchA = fixtures.batch(physics, "A");
        batchB = fixtures.batch(physics, "B");
        batchC = fixtures.batch(fixtures.course("CHEM-2027"), "C");
    }

    private static String body(String title, String startsAt, String endsAt, String timeZone, String status, Integer version,
                               String... batchIds) {
        StringBuilder json = new StringBuilder("{\"title\":\"").append(title).append("\",\"startsAt\":\"").append(startsAt)
                .append("\",\"endsAt\":\"").append(endsAt).append('"');
        if (timeZone != null) {
            json.append(",\"timeZone\":\"").append(timeZone).append('"');
        }
        if (status != null) {
            json.append(",\"status\":\"").append(status).append("\",\"version\":").append(version);
        }
        json.append(",\"batchIds\":[").append(Arrays.stream(batchIds).map(id -> '"' + id + '"').collect(Collectors.joining(",")))
                .append("]}");
        return json.toString();
    }

    private HttpResponse<String> post(String token, String json) {
        return api.post("/api/v1/class-sessions", json, token);
    }

    private Map<String, Object> create(String startsAt, String endsAt, String... batchIds) {
        HttpResponse<String> response = post(admin, body("Lesson", startsAt, endsAt, null, null, null, batchIds));
        assertEquals(201, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private String enrolledStudent(String number, String enrolledOn, String withdrawnOn, String... batches) {
        String id = (String) fixtures.student(number, number.toLowerCase() + "@example.invalid").get("id");
        for (String batch : batches) {
            HttpResponse<String> enrolled = fixtures.enroll(batch, id, enrolledOn);
            assertEquals(201, enrolled.statusCode(), enrolled.body());
            if (withdrawnOn != null) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("enrolledOn", enrolledOn);
                fields.put("withdrawnOn", withdrawnOn);
                fields.put("version", 0);
                assertEquals(200, api.put("/api/v1/enrollments/" + HttpApi.parse(enrolled).get("id"), HttpApi.json(fields), admin)
                        .statusCode());
            }
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(item -> (Map<String, Object>) item).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> batchIdsOf(Map<String, Object> session) {
        return ((List<Map<String, Object>>) session.get("batches")).stream().map(b -> (String) b.get("batchId")).toList();
    }

    private static List<Object> field(List<Map<String, Object>> items, String name) {
        return items.stream().map(item -> item.get(name)).toList();
    }

    private String window(String from, String to) {
        return "/api/v1/class-sessions?from=" + from + "&to=" + to;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void aClassSharedByTwoBatchesIsOneSessionRecord() {
        HttpResponse<String> response = post(admin, body("  Kinematics  ", START, END, null, null, null, batchB, batchA));

        assertEquals(201, response.statusCode(), response.body());
        Map<String, Object> created = HttpApi.parse(response);
        assertEquals("Kinematics", created.get("title"));
        assertEquals(START, created.get("startsAt"));
        assertEquals("Asia/Colombo", created.get("timeZone"), "the academy default applies when no zone is given");
        assertEquals("2027-03-01", created.get("localDate"));
        assertEquals("SCHEDULED", created.get("status"));
        assertEquals(List.of(batchA, batchB), batchIdsOf(created), "batches sorted by code");
        assertEquals("/api/v1/class-sessions/" + created.get("id"), response.headers().firstValue("Location").orElseThrow());
        assertEquals(1, count("class_sessions"));
        assertEquals(2, count("session_batches"));
        assertEquals(created, HttpApi.parse(api.get("/api/v1/class-sessions/" + created.get("id"), admin)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStudentInSeveralOfTheBatchesIsExpectedExactlyOnce() {
        String both = enrolledStudent("PCA-001", "2027-01-10", null, batchA, batchB);
        enrolledStudent("PCA-002", "2027-01-10", null, batchA);
        enrolledStudent("PCA-003", "2027-01-10", null, batchB);
        enrolledStudent("PCA-004", "2027-01-10", null, batchC);
        String session = (String) create(START, END, batchA, batchB).get("id");

        List<Map<String, Object>> expected = list(api.get("/api/v1/class-sessions/" + session + "/students", admin));

        assertEquals(List.of("PCA-001", "PCA-002", "PCA-003"), field(expected, "studentNumber"));
        Map<String, Object> shared = expected.get(0);
        assertEquals(both, shared.get("studentId"));
        assertEquals(List.of(batchA, batchB).stream().sorted().toList(), ((List<String>) shared.get("batchIds")).stream().sorted().toList());
        assertEquals(3, expected.stream().map(s -> s.get("studentId")).distinct().count(), "no student appears twice");
    }

    @Test
    void enrollmentIsJudgedOnTheClassDateWhereItIsHeld() {
        // 20:00 UTC on 1 March is 01:30 on 2 March in Colombo.
        String evening = "2027-03-01T20:00:00Z";
        String late = "2027-03-01T21:00:00Z";
        enrolledStudent("PCA-STARTS-THAT-DAY", "2027-03-02", null, batchC);
        enrolledStudent("PCA-LEFT-THAT-DAY", "2027-01-10", "2027-03-02", batchC);
        enrolledStudent("PCA-STARTS-NEXT-DAY", "2027-03-03", null, batchC);
        enrolledStudent("PCA-LEAVES-NEXT-DAY", "2027-01-10", "2027-03-03", batchC);
        Map<String, Object> colombo = create(evening, late, batchC);
        HttpResponse<String> utcResponse = post(admin, body("Lesson", evening, late, "UTC", null, null, batchC));
        Map<String, Object> utc = HttpApi.parse(utcResponse);

        assertEquals("2027-03-02", colombo.get("localDate"));
        assertEquals("2027-03-01", utc.get("localDate"));
        assertEquals(List.of("PCA-LEAVES-NEXT-DAY", "PCA-STARTS-THAT-DAY"),
                field(list(api.get("/api/v1/class-sessions/" + colombo.get("id") + "/students", admin)), "studentNumber"),
                "a withdrawal date is the first day no longer enrolled");
        assertEquals(List.of("PCA-LEAVES-NEXT-DAY", "PCA-LEFT-THAT-DAY"),
                field(list(api.get("/api/v1/class-sessions/" + utc.get("id") + "/students", admin)), "studentNumber"));
    }

    @Test
    void invalidSessionsAreRejected() {
        String[] twentyOne = IntStream.range(0, 21).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);
        for (String invalid : List.of(
                body("Lesson", START, END, null, null, null),
                body("Lesson", START, END, null, null, null, batchA, batchA),
                body("Lesson", START, END, null, null, null, twentyOne),
                body("Lesson", END, START, null, null, null, batchA),
                body("Lesson", START, START, null, null, null, batchA),
                body("Lesson", START, "2027-03-01T20:31:00Z", null, null, null, batchA),
                body("Lesson", START, END, "Mars/Olympus", null, null, batchA),
                body("Lesson", START, END, "+05:30", null, null, batchA),
                body(" ", START, END, null, null, null, batchA),
                body("Lesson", "yesterday", END, null, null, null, batchA),
                "{\"title\":\"Lesson\",\"startsAt\":\"" + START + "\",\"endsAt\":\"" + END + "\"}")) {
            assertEquals(400, post(admin, invalid).statusCode(), invalid);
        }
        assertEquals(201, post(admin, body("Lesson", START, "2027-03-01T20:30:00Z", null, null, null, batchA)).statusCode(),
                "exactly twelve hours is allowed");
        assertEquals(404, post(admin, body("Lesson", START, END, null, null, null, batchA, UUID.randomUUID().toString())).statusCode());
        assertEquals(1, count("class_sessions"));
        assertEquals(1, count("session_batches"), "a rejected session leaves no batch links");
    }

    @Test
    void foreignAndInactiveBatchesAreRefused() {
        String outsiderAdmin = tokenFor(academy("Other Academy"), Role.STAFF);
        AcademyFixtures theirs = new AcademyFixtures(api, outsiderAdmin);
        String theirBatch = theirs.batch(theirs.course("THEIRS"), "X");
        assertEquals(404, post(admin, body("Lesson", START, END, null, null, null, batchA, theirBatch)).statusCode());

        Map<String, Object> deactivate = new LinkedHashMap<>();
        deactivate.put("code", "B");
        deactivate.put("name", "Batch B");
        deactivate.put("startsOn", "2027-01-10");
        deactivate.put("active", false);
        deactivate.put("version", 0);
        assertEquals(200, api.put("/api/v1/batches/" + batchB, HttpApi.json(deactivate), admin).statusCode());
        assertEquals(409, post(admin, body("Lesson", START, END, null, null, null, batchA, batchB)).statusCode());
        assertEquals(0, count("class_sessions"));
    }

    @Test
    void sessionsAreListedByTimeWindowAndBatch() {
        String day1 = (String) create("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", batchA).get("id");
        String day3 = (String) create("2027-03-03T08:30:00Z", "2027-03-03T10:30:00Z", batchA, batchB).get("id");
        String day2 = (String) create("2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", batchB).get("id");
        create("2027-04-15T08:30:00Z", "2027-04-15T10:30:00Z", batchC);

        assertEquals(List.of(day1, day2, day3), field(list(api.get(window("2027-03-01T00:00:00Z", "2027-03-04T00:00:00Z"), admin)), "id"));
        assertEquals(List.of(day1, day3),
                field(list(api.get(window("2027-03-01T00:00:00Z", "2027-03-04T00:00:00Z") + "&batchId=" + batchA, admin)), "id"));
        assertEquals(List.of(day2), field(list(api.get(window("2027-03-02T08:30:00Z", "2027-03-03T08:30:00Z"), admin)), "id"),
                "from is inclusive, to is exclusive");
        assertEquals(400, api.get(window("2027-03-04T00:00:00Z", "2027-03-01T00:00:00Z"), admin).statusCode());
        assertEquals(400, api.get(window("2027-01-01T00:00:00Z", "2027-04-04T00:00:00Z"), admin).statusCode(), "over 92 days");
        assertEquals(400, api.get("/api/v1/class-sessions", admin).statusCode(), "a window is required");
        assertEquals(404, api.get(window("2027-03-01T00:00:00Z", "2027-03-04T00:00:00Z") + "&batchId=" + UUID.randomUUID(), admin)
                .statusCode());
    }

    @Test
    void updateReplacesTheBatchSetAndChangingOnlyBatchesStillAdvancesTheVersion() {
        Map<String, Object> session = create(START, END, batchA, batchB);
        String path = "/api/v1/class-sessions/" + session.get("id");

        HttpResponse<String> moved = api.put(path, body("Lesson", START, END, null, "SCHEDULED", 0, batchA, batchC), admin);

        assertEquals(200, moved.statusCode(), moved.body());
        Map<String, Object> updated = HttpApi.parse(moved);
        assertEquals(List.of(batchA, batchC), batchIdsOf(updated));
        assertEquals(1, ((Number) updated.get("version")).intValue(), "a batch-only change must be detectable by other editors");
        assertEquals(409, api.put(path, body("Lesson", START, END, null, "SCHEDULED", 0, batchA), admin).statusCode());

        Map<String, Object> cancelled = HttpApi.parse(api.put(path, body("Lesson", START, END, null, "CANCELLED", 1, batchA, batchC), admin));
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(400, api.put(path, body("Lesson", START, END, null, "DELETED", 2, batchA), admin).statusCode());
        assertEquals(404, api.put("/api/v1/class-sessions/" + UUID.randomUUID(), body("L", START, END, null, "SCHEDULED", 0, batchA), admin)
                .statusCode());
        assertEquals(2, count("session_batches"));
    }

    @Test
    void aBatchThatBecameInactiveMayStayButNotBeAdded() {
        Map<String, Object> session = create(START, END, batchA);
        String path = "/api/v1/class-sessions/" + session.get("id");
        for (String batch : List.of(batchA, batchB)) {
            Map<String, Object> deactivate = new LinkedHashMap<>();
            deactivate.put("code", batch.equals(batchA) ? "A" : "B");
            deactivate.put("name", "x");
            deactivate.put("startsOn", "2027-01-10");
            deactivate.put("active", false);
            deactivate.put("version", 0);
            assertEquals(200, api.put("/api/v1/batches/" + batch, HttpApi.json(deactivate), admin).statusCode());
        }

        assertEquals(200, api.put(path, body("Renamed", START, END, null, "SCHEDULED", 0, batchA), admin).statusCode());
        assertEquals(409, api.put(path, body("Renamed", START, END, null, "SCHEDULED", 1, batchA, batchB), admin).statusCode());
    }

    @Test
    void concurrentEditsOfOneVersionHaveOneWinner() throws Exception {
        String path = "/api/v1/class-sessions/" + create(START, END, batchA).get("id");
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, threads).<Callable<Integer>>mapToObj(i -> () -> {
                start.await();
                return api.put(path, body("Edit " + i, START, END, null, "SCHEDULED", 0, i % 2 == 0 ? batchB : batchC), admin)
                        .statusCode();
            }).toList())) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(threads - 1, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(1, count("session_batches"), "the winner's batch set only, never a mix");
    }

    @Test
    void everyStaffMemberSeesAndManagesEveryClassWithAllItsBatches() {
        enrolledStudent("PCA-001", "2027-01-10", null, batchA, batchB);
        enrolledStudent("PCA-002", "2027-01-10", null, batchA);
        enrolledStudent("PCA-003", "2027-01-10", null, batchB);
        String shared = (String) create(START, END, batchA, batchB).get("id");
        String onlyB = (String) create("2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", batchB).get("id");
        String colleague = tokenFor(academyId, Role.STAFF);
        String march = window("2027-03-01T00:00:00Z", "2027-03-05T00:00:00Z");

        List<Map<String, Object>> visible = list(api.get(march, colleague));
        assertEquals(List.of(shared, onlyB), field(visible, "id"));
        assertEquals(List.of(batchA, batchB).stream().sorted().toList(), batchIdsOf(visible.get(0)).stream().sorted().toList());
        assertEquals(List.of("PCA-001", "PCA-002", "PCA-003"),
                field(list(api.get("/api/v1/class-sessions/" + shared + "/students", colleague)), "studentNumber"));
        assertEquals(List.of(shared, onlyB), field(list(api.get(march + "&batchId=" + batchB, colleague)), "id"));
        assertEquals(200, api.put("/api/v1/class-sessions/" + onlyB,
                body("Moved", "2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", null, "CANCELLED", 0, batchB), colleague).statusCode(),
                "any staff member manages any class");
    }

    @Test
    void otherAcademiesAndStudentsCannotReachSessions() {
        String session = (String) create(START, END, batchA).get("id");
        String march = window("2027-03-01T00:00:00Z", "2027-03-05T00:00:00Z");
        String outsider = tokenFor(academy("Other Academy"), Role.STAFF);

        assertEquals(404, api.get("/api/v1/class-sessions/" + session, outsider).statusCode());
        assertEquals(404, api.get("/api/v1/class-sessions/" + session + "/students", outsider).statusCode());
        assertEquals(404, api.put("/api/v1/class-sessions/" + session, body("X", START, END, null, "CANCELLED", 0, batchA), outsider)
                .statusCode());
        assertEquals(List.of(), list(api.get(march, outsider)));
        assertEquals(404, post(outsider, body("X", START, END, null, null, null, batchA)).statusCode());

        String studentId = enrolledStudent("PCA-001", "2027-01-10", null, batchA);
        activate(UUID.fromString((String) HttpApi.parse(api.get("/api/v1/students/" + studentId, admin)).get("accountId")));
        for (String token : List.of(login("pca-001@example.invalid"))) {
            assertEquals(403, api.get(march, token).statusCode());
            assertEquals(403, api.get("/api/v1/class-sessions/" + session, token).statusCode());
            assertEquals(403, api.get("/api/v1/class-sessions/" + session + "/students", token).statusCode());
        }
        assertEquals(401, api.get(march, null).statusCode());
        assertEquals("SCHEDULED", HttpApi.parse(api.get("/api/v1/class-sessions/" + session, admin)).get("status"));
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        UUID other = academy("Other Academy");
        String insertSession = "INSERT INTO class_sessions (id, academy_id, title, starts_at, ends_at, time_zone, status) "
                + "VALUES (?, ?, 'T', ?, ?, 'UTC', ?)";
        Timestamp start = Timestamp.from(Instant.parse(START));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insertSession, UUID.randomUUID(), academyId, start,
                start, "SCHEDULED"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insertSession, UUID.randomUUID(), academyId, start,
                Timestamp.from(Instant.parse(START).plusSeconds(13 * 3600)), "SCHEDULED"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insertSession, UUID.randomUUID(), academyId, start,
                Timestamp.from(Instant.parse(END)), "DONE"));

        UUID session = UUID.fromString((String) create(START, END, batchA).get("id"));
        String link = "INSERT INTO session_batches (session_id, batch_id, academy_id) VALUES (?, ?::uuid, ?)";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(link, session, batchA, academyId), "duplicate link");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(link, session, batchB, other),
                "session and batch must share the link's academy");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM batches WHERE id = ?::uuid", batchA));
    }
}
