package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** The academy's rule and final records (004B): only from Zoom's report, once per class, rule kept with each record. */
class AttendanceFinalisationIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";
    private static final String OCCURRENCE = "Final==";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;

    UUID academyId;
    String owner;
    String admin;
    String batch;
    String session;
    Map<String, String> students = new LinkedHashMap<>();

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        owner = tokenFor(academyId, Role.STAFF);
        admin = tokenFor(academyId, Role.STAFF);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        for (String n : List.of("1", "2", "3", "4", "5")) {
            students.put(n, (String) fixtures.student("S-00" + n, "student" + n + "@example.test").get("id"));
            if (!n.equals("5")) {
                assertEquals(201, fixtures.enroll(batch, students.get(n), "2026-01-01").statusCode());
            }
        }
        session = session("2026-02-02T08:30:00Z", "2026-02-02T10:30:00Z");
        // Live events link the occurrence to the class; the report below is what counts.
        long ts = Instant.parse("2026-02-02T08:29:00Z").toEpochMilli();
        String json = "{\"event\":\"webinar.started\",\"event_ts\":" + ts + ",\"payload\":{\"object\":{\"id\":\"" + WEBINAR
                + "\",\"uuid\":\"" + OCCURRENCE + "\",\"host_id\":\"hostuser\"}}}";
        ZoomTestReceipts.store(context, academyId, "webinar.started", ts, json.getBytes(StandardCharsets.UTF_8));
        processor.runOnce();
    }

    private String session(String startsAt, String endsAt) {
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Optics\",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\""
                + endsAt + "\",\"batchIds\":[\"" + batch + "\"],\"zoomWebinarId\":\"" + WEBINAR + "\"}", admin);
        assertEquals(201, created.statusCode(), created.body());
        return (String) HttpApi.parse(created).get("id");
    }

    /** Zoom's report, as ZoomReportService stores it. */
    private void report() {
        jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, fetched_at, join_sessions) "
                + "VALUES (?, ?, 'FETCHED', 1, now(), 4)", academyId, OCCURRENCE);
        row("student1@example.test", "08:30", "10:30"); // whole class
        row("student2@example.test", "08:45", "10:30"); // 87.5 %, 15 minutes late
        row("student3@example.test", "08:50", "09:20"); // 25 %
        row("student5@example.test", "08:30", "10:30"); // not expected: no record
    }

    private void row(String email, String from, String to) {
        jdbc.update("INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, display_name, joined_at, left_at, duration_seconds) "
                + "VALUES (?, ?, ?, ?, 'Someone', ?, ?, 0)", UUID.randomUUID(), academyId, OCCURRENCE, email,
                Timestamp.from(Instant.parse("2026-02-02T" + from + ":00Z")), Timestamp.from(Instant.parse("2026-02-02T" + to + ":00Z")));
    }

    private HttpResponse<String> setPolicy(String token, int percent, int late, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minConnectedPercent", percent);
        body.put("lateAfterMinutes", late);
        body.put("description", "Approved by the academy board, 1 Nov 2026");
        body.put("version", version);
        return api.put("/api/v1/attendance-policy", HttpApi.json(body), token);
    }

    private HttpResponse<String> finalise(String id, String token) {
        return api.post("/api/v1/class-sessions/" + id + "/attendance/finalisation", "{}", token);
    }

    @Test
    void onlyStaffRecordTheRuleAndChangesAreVersionedAndAudited() {
        assertEquals(204, api.get("/api/v1/attendance-policy", admin).statusCode(), "no rule yet");
        assertEquals(403, setPolicy(tokenFor(academyId, Role.STUDENT), 75, 10, null).statusCode(), "students cannot set it");
        assertEquals(400, setPolicy(owner, 0, 10, null).statusCode());
        assertEquals(400, setPolicy(owner, 75, 241, null).statusCode());
        HttpResponse<String> first = setPolicy(owner, 75, 10, null);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(0, ((Number) HttpApi.parse(first).get("version")).intValue());
        assertEquals(409, setPolicy(owner, 80, 5, null).statusCode(), "a stale first-time save does not overwrite");
        assertEquals(200, setPolicy(owner, 80, 5, 0L).statusCode());
        Map<String, Object> current = HttpApi.parse(api.get("/api/v1/attendance-policy", tokenFor(academyId, Role.STAFF)));
        assertEquals(80, current.get("minConnectedPercent"));
        assertEquals(1, ((Number) current.get("version")).intValue());
        assertEquals(List.of("none", "75% / 10 min"), jdbc.queryForList("SELECT details->>'previous' FROM audit_events WHERE academy_id = ? "
                + "AND action = 'ATTENDANCE_POLICY_SET' ORDER BY occurred_at", String.class, academyId));
        assertEquals(403, api.get("/api/v1/attendance-policy", tokenFor(academyId, Role.STUDENT)).statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClassIsFinalisedOnlyFromZoomsReportAndOnlyOnce() {
        assertEquals(409, finalise(session, admin).statusCode(), "no rule yet");
        assertEquals(200, setPolicy(owner, 75, 10, null).statusCode());
        HttpResponse<String> live = finalise(session, admin);
        assertEquals(409, live.statusCode());
        assertTrue(live.body().contains("live events alone are never final"), live.body());

        report();
        HttpResponse<String> done = finalise(session, admin);
        assertEquals(200, done.statusCode(), done.body());
        Map<String, Object> counts = HttpApi.parse(done);
        assertEquals(1, counts.get("present"));
        assertEquals(1, counts.get("late"));
        assertEquals(2, counts.get("absent"), "too short, and never seen");
        assertEquals(409, finalise(session, admin).statusCode(), "once per class");

        Map<String, Object> view = HttpApi.parse(api.get("/api/v1/class-sessions/" + session + "/attendance", admin));
        assertNotNull(view.get("finalisedAt"));
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) view.get("students")) {
            outcomes.put((String) s.get("studentNumber"), (String) s.get("outcome"));
        }
        assertEquals(Map.of("S-001", "PRESENT", "S-002", "LATE", "S-003", "ABSENT", "S-004", "ABSENT"),
                Map.copyOf(outcomes.entrySet().stream().filter(e -> e.getValue() != null)
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        assertEquals(null, outcomes.get("S-005"), "seen but not expected: no record");

        // The rule used is kept with each record; a later rule change does not rewrite it.
        assertEquals(200, setPolicy(owner, 50, 30, 0L).statusCode());
        assertEquals(List.of(75), jdbc.queryForList("SELECT DISTINCT rule_min_connected_percent FROM attendance_records "
                + "WHERE class_session_id = ?::uuid", Integer.class, session));
        String csv = api.get("/api/v1/class-sessions/" + session + "/attendance/export", admin).body();
        assertTrue(csv.contains(",S-002,") && csv.lines().filter(l -> l.contains(",S-002,")).findFirst().orElseThrow().endsWith(",LATE"), csv);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE academy_id = ? AND action = 'ATTENDANCE_FINALISED'",
                Integer.class, academyId));
    }

    @Test
    void unfinishedOrCancelledClassesAndOtherRolesAreRefused() {
        assertEquals(200, setPolicy(owner, 75, 10, null).statusCode());
        String future = session("2099-02-02T08:30:00Z", "2099-02-02T10:30:00Z");
        HttpResponse<String> early = finalise(future, admin);
        assertEquals(409, early.statusCode());
        assertTrue(early.body().contains("not finished"), early.body());

        long version = ((Number) HttpApi.parse(api.get("/api/v1/class-sessions/" + session, admin)).get("version")).longValue();
        assertEquals(200, api.put("/api/v1/class-sessions/" + session, "{\"title\":\"Optics\",\"startsAt\":\"2026-02-02T08:30:00Z\","
                + "\"endsAt\":\"2026-02-02T10:30:00Z\",\"batchIds\":[\"" + batch + "\"],\"status\":\"CANCELLED\",\"version\":" + version + "}",
                admin).statusCode());
        report();
        assertEquals(409, finalise(session, admin).statusCode());

        assertEquals(403, finalise(session, tokenFor(academyId, Role.STUDENT)).statusCode());
        assertEquals(404, finalise(session, tokenFor(academy("Other"), Role.STAFF)).statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFinalClassIsRecalculatedFromTheCurrentReportKeepingCorrections() {
        assertEquals(200, setPolicy(owner, 75, 10, null).statusCode());
        report();
        assertEquals(409, api.post("/api/v1/class-sessions/" + session + "/attendance/recalculation", "{\"reason\":\"Too early\"}", admin)
                .statusCode(), "not final yet");
        assertEquals(200, finalise(session, admin).statusCode());
        Map<String, String> records = new LinkedHashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) HttpApi.parse(api.get("/api/v1/class-sessions/" + session + "/attendance", admin))
                .get("students")) {
            records.put((String) s.get("studentNumber"), (String) s.get("recordId"));
        }
        // S-004 was never seen and is excused by hand; then Zoom's report is fetched again with S-003 there all class.
        assertEquals(200, api.post("/api/v1/attendance-records/" + records.get("S-004") + "/corrections",
                "{\"outcome\":\"EXCUSED\",\"reason\":\"Medical note\",\"version\":0}", admin).statusCode());
        jdbc.update("DELETE FROM zoom_report_participants WHERE academy_id = ? AND email = 'student3@example.test'", academyId);
        row("student3@example.test", "08:30", "10:30");
        row("student4@example.test", "08:30", "10:30");

        assertEquals(400, api.post("/api/v1/class-sessions/" + session + "/attendance/recalculation", "{\"reason\":\"\"}", admin).statusCode());
        HttpResponse<String> done = api.post("/api/v1/class-sessions/" + session + "/attendance/recalculation",
                "{\"reason\":\"Zoom report fetched again\"}", admin);
        assertEquals(200, done.statusCode(), done.body());
        assertEquals(1, HttpApi.parse(done).get("changed"), "S-003 absent → present");
        assertEquals(1, HttpApi.parse(done).get("kept"), "S-004's correction stays although the rule now says present");
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) HttpApi.parse(api.get("/api/v1/class-sessions/" + session + "/attendance", admin))
                .get("students")) {
            outcomes.put((String) s.get("studentNumber"), (String) s.get("outcome"));
        }
        assertEquals("PRESENT", outcomes.get("S-003"));
        assertEquals("EXCUSED", outcomes.get("S-004"));
        assertEquals(403, api.post("/api/v1/class-sessions/" + session + "/attendance/recalculation", "{\"reason\":\"Student\"}",
                tokenFor(academyId, Role.STUDENT)).statusCode());
    }
}
