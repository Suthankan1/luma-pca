package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import com.physicscube.luma.zoom.ZoomReceiptProcessor;
import com.physicscube.luma.zoom.ZoomTestReceipts;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The Academy's example: a two-hour webinar, a student connected 25 + 25 + 20 minutes with two rejoins (and one
 * overlapping duplicate connection) is in the class for 70 minutes, once, on every dashboard.
 */
class AttendanceInsightsIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234500077";
    private static final String CLASS_TOTALS = "/api/v1/attendance/class-totals";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String course;
    String batch;
    String amaya;
    String kavin;
    UUID amayaAccount;
    String classId;
    Instant start;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        fixtures = new AcademyFixtures(api, admin);
        course = fixtures.course("PHY-2027");
        batch = fixtures.batch(course, "MON");
        Map<String, Object> a = fixtures.student("PCA001", "amaya@example.test");
        amaya = (String) a.get("id");
        amayaAccount = UUID.fromString((String) a.get("accountId"));
        kavin = (String) fixtures.student("PCA002", "kavin@example.test").get("id");
        for (String student : List.of(amaya, kavin)) {
            assertEquals(201, fixtures.enroll(batch, student, "2026-01-01").statusCode());
        }
        start = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Waves\",\"startsAt\":\"" + start
                + "\",\"endsAt\":\"" + start.plus(2, ChronoUnit.HOURS) + "\",\"batchIds\":[\"" + batch + "\"],\"zoomWebinarId\":\""
                + WEBINAR + "\"}", admin);
        assertEquals(201, created.statusCode(), created.body());
        classId = (String) HttpApi.parse(created).get("id");

        long ts = start.toEpochMilli();
        ZoomTestReceipts.store(context, academyId, "webinar.started", ts, ("{\"event\":\"webinar.started\",\"event_ts\":" + ts
                + ",\"payload\":{\"object\":{\"id\":\"" + WEBINAR + "\",\"uuid\":\"Waves==\",\"host_id\":\"h\"}}}").getBytes(StandardCharsets.UTF_8));
        processor.runOnce();
        jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, fetched_at, join_sessions) "
                + "VALUES (?, 'Waves==', 'FETCHED', 1, now(), 4)", academyId);
        visit(0, 25);
        visit(35, 60);
        visit(50, 55); // a duplicate connection inside another: never counted twice
        visit(70, 90);
    }

    private void visit(int fromMinute, int toMinute) {
        jdbc.update("INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, display_name, joined_at, left_at, "
                + "duration_seconds) VALUES (?, ?, 'Waves==', 'amaya@example.test', 'Amaya', ?, ?, ?)", UUID.randomUUID(), academyId,
                Timestamp.from(start.plus(fromMinute, ChronoUnit.MINUTES)), Timestamp.from(start.plus(toMinute, ChronoUnit.MINUTES)),
                (toMinute - fromMinute) * 60);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private Map<String, Object> ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private List<Object> classTotals(String token) {
        HttpResponse<String> response = api.get(CLASS_TOTALS, token);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response);
    }

    @Test
    void theDashboardShowsEachStudentsTimeAndShareOfTheClass() {
        Map<String, Object> dashboard = ok(api.get("/api/v1/attendance/dashboard?courseId=" + course, admin));
        Map<String, Object> totals = map(dashboard.get("totals"));
        assertEquals(1, totals.get("classes"));
        assertEquals(2, totals.get("students"));
        assertEquals(7200, ((Number) totals.get("classSeconds")).intValue());

        Map<String, Object> theClass = map(list(dashboard.get("classes")).get(0));
        assertEquals("Waves", theClass.get("title"));
        assertEquals("REPORT", theClass.get("state"));
        assertEquals(2, theClass.get("expected"));
        assertEquals(1, theClass.get("joined"));

        List<Object> students = list(dashboard.get("students"));
        Map<String, Object> first = map(students.get(0));
        assertEquals("PCA001", first.get("studentNumber"));
        assertEquals(4200, ((Number) first.get("connectedSeconds")).intValue(), "25 + 25 + 20 minutes, overlap counted once");
        assertEquals(58, first.get("percent"));
        assertEquals(3, first.get("rejoins"));
        assertEquals(List.of("MON"), first.get("batchCodes"));
        Map<String, Object> cell = map(list(first.get("cells")).get(0));
        assertEquals(4, cell.get("visits"));
        assertNull(cell.get("outcome"), "not finalised yet");

        Map<String, Object> second = map(students.get(1));
        assertEquals("PCA002", second.get("studentNumber"));
        assertEquals(0, ((Number) second.get("connectedSeconds")).intValue());
        assertEquals(0, second.get("joined"));

        assertEquals(1, map(ok(api.get("/api/v1/attendance/dashboard?batchId=" + batch, admin)).get("totals")).get("classes"));
        String otherCourse = fixtures.course("CHEM-2027");
        assertEquals(0, map(ok(api.get("/api/v1/attendance/dashboard?courseId=" + otherCourse, admin)).get("totals")).get("classes"));
        assertEquals(400, api.get("/api/v1/attendance/dashboard?from=2026-01-01&to=2026-12-31", admin).statusCode());
    }

    @Test
    void oneStudentsPageListsCoursesAndEveryJoinAndLeave() {
        Map<String, Object> overview = ok(api.get("/api/v1/students/" + amaya + "/attendance-overview", admin));
        assertEquals("PCA001", overview.get("studentNumber"));
        Map<String, Object> enrolment = map(list(overview.get("enrolments")).get(0));
        assertEquals("PHY-2027", enrolment.get("courseCode"));
        assertEquals("MON", enrolment.get("batchCode"));
        Map<String, Object> overall = map(overview.get("overall"));
        assertEquals(1, overall.get("classes"));
        assertEquals(4200, ((Number) overall.get("connectedSeconds")).intValue());
        Map<String, Object> theClass = map(list(overview.get("classes")).get(0));
        assertEquals(58, theClass.get("percent"));
        assertEquals(3, theClass.get("rejoins"));
        List<Object> timeline = list(theClass.get("timeline"));
        assertEquals(4, timeline.size());
        assertEquals(start.toString(), map(timeline.get(0)).get("joinedAt").toString().replace(".000", ""));
        assertEquals(1500, ((Number) map(timeline.get(0)).get("secondsInClass")).intValue());
        assertEquals(1, list(overview.get("byBatch")).size());
    }

    @Test
    void aStudentSeesOnlyTheirOwnAttendance() {
        activate(amayaAccount);
        String student = login("amaya@example.test");
        Map<String, Object> mine = ok(api.get("/api/v1/me/attendance-overview", student));
        assertEquals("PCA001", mine.get("studentNumber"));
        assertEquals(4200, ((Number) map(mine.get("overall")).get("connectedSeconds")).intValue());
        assertEquals(4, list(map(list(mine.get("classes")).get(0)).get("timeline")).size());

        assertEquals(403, api.get("/api/v1/students/" + kavin + "/attendance-overview", student).statusCode());
        assertEquals(403, api.get("/api/v1/attendance/dashboard", student).statusCode());
        assertEquals(403, api.get("/api/v1/me/attendance-overview", admin).statusCode());
    }

    @Test
    void staffSeeOnlyTheirBatchesAndOtherAcademiesNothing() {
        String instructor = tokenFor(academyId, Role.INSTRUCTOR);
        assertEquals(0, map(ok(api.get("/api/v1/attendance/dashboard", instructor)).get("totals")).get("classes"),
                "not assigned to any batch");
        UUID other = academy("Other academy");
        String otherAdmin = tokenFor(other, Role.ACADEMY_ADMIN);
        assertEquals(0, map(ok(api.get("/api/v1/attendance/dashboard", otherAdmin)).get("totals")).get("classes"));
        assertEquals(404, api.get("/api/v1/students/" + amaya + "/attendance-overview", otherAdmin).statusCode());
        assertTrue(api.get("/api/v1/attendance/dashboard", null).statusCode() == 401);
    }

    @Test
    void classTotalsCountEachExpectedStudentAndTheirRejoinsOnce() {
        String otherBatch = fixtures.batch(course, "TUE");
        assertEquals(201, fixtures.enroll(otherBatch, kavin, "2026-01-01").statusCode());
        jdbc.update("INSERT INTO session_batches (session_id, batch_id, academy_id) VALUES (?::uuid, ?::uuid, ?)",
                classId, otherBatch, academyId);

        assertEquals(List.of(Map.of("id", classId, "expected", 2, "joined", 1)), classTotals(admin));

        String instructorEmail = "teacher@example.test";
        accountIdOf(instructorEmail, academyId, Role.INSTRUCTOR);
        String instructor = login(instructorEmail);
        assertEquals(List.of(), classTotals(instructor), "no assigned batches");
        assertEquals(201, api.post("/api/v1/batches/" + otherBatch + "/assignments",
                HttpApi.json(Map.of("email", instructorEmail, "role", "INSTRUCTOR")), admin).statusCode());
        assertEquals(List.of(Map.of("id", classId, "expected", 1, "joined", 0)), classTotals(instructor),
                "Amaya is outside the instructor's batch even though she joined the shared class");
    }

    @Test
    void classTotalsRequireStaffAndStayInsideTheAcademy() {
        assertEquals(401, api.get(CLASS_TOTALS, null).statusCode());
        assertEquals(403, api.get(CLASS_TOTALS, tokenFor(academyId, Role.STUDENT)).statusCode());
        assertEquals(403, api.get(CLASS_TOTALS, tokenFor(academyId, Role.ANALYST)).statusCode());
        assertEquals(List.of(), classTotals(tokenFor(academy("Other academy"), Role.ACADEMY_ADMIN)));
    }

    @Test
    void classTotalsOmitFutureRunningAndCancelledClassesAndKeepEmptyRosters() {
        String emptyBatch = fixtures.batch(course, "EMPTY");
        Instant now = Instant.now();
        String empty = createClass("Empty roster", start.plus(1, ChronoUnit.DAYS), emptyBatch);
        String cancelled = createClass("Cancelled", start.plus(2, ChronoUnit.DAYS), batch);
        jdbc.update("UPDATE class_sessions SET status = 'CANCELLED' WHERE id = ?::uuid", cancelled);
        createClass("Running", now.minus(1, ChronoUnit.HOURS), batch);
        createClass("Future", now.plus(1, ChronoUnit.DAYS), batch);

        List<Object> totals = classTotals(admin);
        assertEquals(2, totals.size());
        assertTrue(totals.contains(Map.of("id", classId, "expected", 2, "joined", 1)));
        assertTrue(totals.contains(Map.of("id", empty, "expected", 0, "joined", 0)));
        assertEquals(400, api.get(CLASS_TOTALS + "?from=2026-01-01&to=2026-12-31", admin).statusCode());
    }

    private String createClass(String title, Instant startsAt, String batchId) {
        HttpResponse<String> response = api.post("/api/v1/class-sessions", "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt
                + "\",\"endsAt\":\"" + startsAt.plus(2, ChronoUnit.HOURS) + "\",\"batchIds\":[\"" + batchId + "\"]}", admin);
        assertEquals(201, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("id");
    }
}
