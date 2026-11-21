package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * The Academy's example: a two-hour webinar, a student connected 25 + 25 + 20 minutes with two rejoins (and one
 * overlapping duplicate connection) is in the class for 70 minutes, once, on every dashboard.
 */
@TestPropertySource(properties = "luma.attendance.projection.enabled=true")
class AttendanceInsightsIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234500077";
    private static final String CLASS_TOTALS = "/api/v1/attendance/class-totals";
    private static final String LATEST_COMPLETED = "/api/v1/attendance/latest-completed";
    private static final String RECENT_COMPLETED = "/api/v1/attendance/recent-completed";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;

    @Autowired
    AttendanceProjectionStore projections;

    @Autowired
    ProvisionalAttendanceService attendance;

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
        admin = tokenFor(academyId, Role.STAFF);
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

    private List<Map<String, Object>> recentCompleted(String token) {
        HttpResponse<String> response = api.get(RECENT_COMPLETED, token);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(AttendanceInsightsIntegrationTest::map).toList();
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
    void onlineHistoryUsesRecordedReportVisitsAndDeduplicatesOverlaps() {
        Map<String, Object> attendance = ok(api.get("/api/v1/class-sessions/" + classId
                + "/attendance?includeOnlineHistory=true", admin));
        assertEquals("RECONCILED", attendance.get("status"));
        List<Object> history = list(attendance.get("onlineHistory"));
        assertEquals(Map.of("at", start.toString(), "online", 1), map(history.getFirst()));
        assertEquals(Map.of("at", start.plus(30, ChronoUnit.MINUTES).toString(), "online", 0), map(history.get(60)));
        assertEquals(Map.of("at", start.plus(50, ChronoUnit.MINUTES).toString(), "online", 1), map(history.get(100)));
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
    void everyStaffMemberSeesTheWholeAcademyAndOtherAcademiesNothing() {
        String colleague = tokenFor(academyId, Role.STAFF);
        assertEquals(map(ok(api.get("/api/v1/attendance/dashboard", admin)).get("totals")),
                map(ok(api.get("/api/v1/attendance/dashboard", colleague)).get("totals")), "no batch assignments any more");
        assertEquals(200, api.get("/api/v1/students/" + amaya + "/attendance-overview", colleague).statusCode());
        UUID other = academy("Other academy");
        String otherAdmin = tokenFor(other, Role.STAFF);
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
        Map<String, Object> latest = ok(api.get(LATEST_COMPLETED, admin));
        assertEquals(classId, latest.get("id"));
        assertEquals("Waves", latest.get("title"));
        assertEquals(start.plus(2, ChronoUnit.HOURS), Instant.parse((String) latest.get("endsAt")));
        assertEquals("Asia/Colombo", latest.get("timeZone"));
        assertEquals(2, latest.get("expected"));
        assertEquals(1, latest.get("joined"));

        String colleague = tokenFor(academyId, Role.STAFF);
        assertEquals(classTotals(admin), classTotals(colleague));
        assertEquals(List.of(latest), recentCompleted(colleague));
        List<String> newer = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            newer.add(createClass("More recent class in MON only", start.plus(day, ChronoUnit.DAYS), batch));
        }
        assertEquals(newer.getLast(), ok(api.get(LATEST_COMPLETED, colleague)).get("id"));
        assertEquals(newer.reversed(), recentCompleted(colleague).stream().map(row -> row.get("id")).toList());
    }

    @Test
    void classTotalsRequireStaffAndStayInsideTheAcademy() {
        String student = tokenFor(academyId, Role.STUDENT);
        for (String path : List.of(CLASS_TOTALS, LATEST_COMPLETED, RECENT_COMPLETED)) {
            assertEquals(401, api.get(path, null).statusCode());
            assertEquals(403, api.get(path, student).statusCode());
        }
        String otherAdmin = tokenFor(academy("Other academy"), Role.STAFF);
        assertEquals(List.of(), classTotals(otherAdmin));
        assertEquals(204, api.get(LATEST_COMPLETED, otherAdmin).statusCode());
        assertEquals(List.of(), recentCompleted(otherAdmin));
    }

    @Test
    void compactStudentDirectoryIncludesUnenrolledStudentsAndPreservesMonthlyAttendance() {
        String unenrolled = (String) fixtures.student("PCA003", "unassigned@example.test").get("id");
        String path = "/api/v1/attendance/student-directory?from=" + start.minus(1, ChronoUnit.DAYS).toString().substring(0, 10)
                + "&to=" + start.plus(1, ChronoUnit.DAYS).toString().substring(0, 10);
        assertEquals(401, api.get(path, null).statusCode());
        assertEquals(403, api.get(path, tokenFor(academyId, Role.STUDENT)).statusCode());
        assertEquals(List.of(), list(ok(api.get(path, tokenFor(academy("Other directory academy"), Role.STAFF))).get("students")));
        var response = api.get(path, admin);
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertFalse(response.body().contains("example.test"));
        var directory = ok(response);
        assertEquals(List.of(Map.of("key", "type:waves", "label", "Waves")), directory.get("classTypes"));
        List<Map<String, Object>> rows = list(directory.get("students")).stream().map(AttendanceInsightsIntegrationTest::map).toList();
        assertEquals(List.of(amaya, kavin, unenrolled), rows.stream().map(row -> row.get("id")).toList());
        assertEquals(Map.of("type:waves", Map.of("expected", 1, "joined", 1)), rows.get(0).get("types"));
        assertEquals(Map.of("type:waves", Map.of("expected", 1, "joined", 0)), rows.get(1).get("types"));
        assertEquals(Map.of(), rows.get(2).get("types"));
    }

    @Test
    void completedProjectionMatchesSourceAndRejectsStalePublication() {
        UUID session = UUID.fromString(classId);
        String dashboard = "/api/v1/attendance/dashboard";
        Map<String, Object> before = ok(api.get(dashboard, admin));
        List<Object> totalsBefore = classTotals(admin);
        var first = projections.claim(1).getFirst();
        var visits = attendance.projectableVisits(academyId, first.window());
        assertEquals(Map.of(UUID.fromString(amaya), new AttendanceProjectionStore.ProjectedVisit(4, 4200)), visits);
        assertTrue(projections.publish(first, visits));
        assertEquals(visits, projections.ready(academyId, java.util.Set.of(session)).get(session));
        assertEquals(before, ok(api.get(dashboard, admin)));
        assertEquals(totalsBefore, classTotals(admin));
        assertEquals(Map.of(), projections.ready(academy("Other projection academy"), java.util.Set.of(session)));

        jdbc.update("DELETE FROM zoom_report_participants WHERE academy_id = ? AND occurrence_uuid = 'Waves=='", academyId);
        assertEquals(Map.of(), projections.ready(academyId, java.util.Set.of(session)));
        assertEquals(List.of(Map.of("id", classId, "expected", 2, "joined", 0)), classTotals(admin));
        var next = projections.claim(1).getFirst();
        assertFalse(projections.publish(first, visits));
        assertTrue(projections.publish(next, attendance.projectableVisits(academyId, next.window())));
        assertEquals(Map.of(session, Map.of()), projections.ready(academyId, java.util.Set.of(session)));

        jdbc.update("UPDATE accounts SET email = 'changed@example.test' WHERE id = ?", amayaAccount);
        assertEquals(Map.of(), projections.ready(academyId, java.util.Set.of(session)));
        assertFalse(projections.publish(next, Map.of()));
    }

    @Test
    void concurrentWorkersClaimOnlyOneLeaseAndErasureRemovesPublishedVisits() throws Exception {
        var startTogether = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<List<AttendanceProjectionStore.Job>> claim = () -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return projections.claim(1);
            };
            var one = executor.submit(claim);
            var two = executor.submit(claim);
            var results = new ArrayList<>(one.get(10, TimeUnit.SECONDS));
            results.addAll(two.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.size());
            var job = results.getFirst();
            assertTrue(projections.publish(job, attendance.projectableVisits(academyId, job.window())));
        }
        assertEquals(200, api.post("/api/v1/students/" + amaya + "/erasure",
                "{\"confirmStudentNumber\":\"PCA001\",\"reason\":\"Requested by student\"}", admin).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM attendance_projected_visits WHERE academy_id = ?", Integer.class, academyId));
        assertEquals(Map.of(), projections.ready(academyId, java.util.Set.of(UUID.fromString(classId))));
    }

    @Test
    void batchedTotalsKeepHostExclusionAndRegistrationMatchingInTheRightClass() {
        Instant secondStart = start.plus(1, ChronoUnit.DAYS);
        String second = createClass("Second class", secondStart, batch);
        jdbc.update("""
                INSERT INTO zoom_occurrence_links (academy_id, occurrence_uuid, webinar_id, class_session_id, link_source, linked_at)
                VALUES (?, 'second==', ?, ?::uuid, 'AUTOMATIC', now())
                """, academyId, WEBINAR, second);
        jdbc.update("""
                INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, fetched_at, join_sessions)
                VALUES (?, 'second==', 'FETCHED', now(), 2)
                """, academyId);
        Timestamp joined = Timestamp.from(secondStart);
        Timestamp left = Timestamp.from(secondStart.plus(25, ChronoUnit.MINUTES));
        jdbc.update("""
                INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, joined_at, left_at,
                      duration_seconds, host, registrant_email)
                VALUES (gen_random_uuid(), ?, 'second==', 'amaya@example.test', ?, ?, 1500, true, NULL),
                       (gen_random_uuid(), ?, 'second==', 'other-zoom@example.test', ?, ?, 1500, false, 'kavin@example.test')
                """, academyId, joined, left, academyId, joined, left);

        assertEquals(List.of(Map.of("id", classId, "expected", 2, "joined", 1),
                Map.of("id", second, "expected", 2, "joined", 1)), classTotals(admin));
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
        assertEquals(empty, ok(api.get(LATEST_COMPLETED, admin)).get("id"));
        assertEquals(List.of(empty, classId), recentCompleted(admin).stream().map(row -> row.get("id")).toList());
        assertEquals(400, api.get(CLASS_TOTALS + "?from=2026-01-01&to=2026-12-31", admin).statusCode());
    }

    @Test
    void latestCompletedUsesEndTimeWithNoDateCutoff() {
        Instant older = start.minus(100, ChronoUnit.DAYS);
        jdbc.update("UPDATE class_sessions SET starts_at = ?, ends_at = ? WHERE id = ?::uuid",
                Timestamp.from(older), Timestamp.from(older.plus(4, ChronoUnit.HOURS)), classId);
        String endedSooner = createClass("Started later, ended sooner", older.plus(1, ChronoUnit.HOURS), batch);

        assertEquals(classId, ok(api.get(LATEST_COMPLETED, admin)).get("id"));
        assertEquals(List.of(classId, endedSooner), recentCompleted(admin).stream().map(row -> row.get("id")).toList());
    }

    private String createClass(String title, Instant startsAt, String batchId) {
        HttpResponse<String> response = api.post("/api/v1/class-sessions", "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt
                + "\",\"endsAt\":\"" + startsAt.plus(2, ChronoUnit.HOURS) + "\",\"batchIds\":[\"" + batchId + "\"]}", admin);
        assertEquals(201, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("id");
    }
}
