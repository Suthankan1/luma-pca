package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/** Corrections, student history and appeals (004C), with notices by email. */
@org.springframework.test.context.TestPropertySource(properties = {
    "luma.mail.enabled=true",
    "luma.mail.from=no-reply@example.invalid",
    "luma.mail.web-origin=https://luma.example.invalid",
    "spring.mail.host=localhost"})
@org.springframework.context.annotation.Import(com.physicscube.luma.identity.CapturedMail.class)
class AttendanceCorrectionIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";
    private static final String OCCURRENCE = "Corr==";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;

    UUID academyId;
    String admin;
    String batchA;
    String batchB;
    String session;
    String studentA;
    String studentB;
    String recordA;
    String recordB;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void finalisedClass() {
        com.physicscube.luma.identity.CapturedMail.SENT.clear();
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String course = fixtures.course("PHY-2026");
        batchA = fixtures.batch(course, "A");
        batchB = fixtures.batch(course, "B");
        Map<String, Object> a = fixtures.student("S-001", "corr.a@example.test");
        Map<String, Object> b = fixtures.student("S-002", "corr.b@example.test");
        studentA = (String) a.get("id");
        studentB = (String) b.get("id");
        activate(UUID.fromString((String) a.get("accountId")));
        assertEquals(201, fixtures.enroll(batchA, studentA, "2026-01-01").statusCode());
        assertEquals(201, fixtures.enroll(batchB, studentB, "2026-01-01").statusCode());
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Optics\",\"startsAt\":\"2026-02-02T08:30:00Z\","
                + "\"endsAt\":\"2026-02-02T10:30:00Z\",\"batchIds\":[\"" + batchA + "\",\"" + batchB + "\"],\"zoomWebinarId\":\"" + WEBINAR + "\"}", admin);
        assertEquals(201, created.statusCode(), created.body());
        session = (String) HttpApi.parse(created).get("id");
        long ts = Instant.parse("2026-02-02T08:29:00Z").toEpochMilli();
        String json = "{\"event\":\"webinar.started\",\"event_ts\":" + ts + ",\"payload\":{\"object\":{\"id\":\"" + WEBINAR + "\",\"uuid\":\""
                + OCCURRENCE + "\",\"host_id\":\"hostuser\"}}}";
        ZoomTestReceipts.store(context, academyId, "webinar.started", ts, json.getBytes(StandardCharsets.UTF_8));
        processor.runOnce();
        jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, fetched_at, join_sessions) "
                + "VALUES (?, ?, 'FETCHED', 1, now(), 1)", academyId, OCCURRENCE);
        jdbc.update("INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, joined_at, left_at, duration_seconds) "
                + "VALUES (?, ?, ?, 'corr.b@example.test', ?, ?, 7200)", UUID.randomUUID(), academyId, OCCURRENCE,
                Timestamp.from(Instant.parse("2026-02-02T08:30:00Z")), Timestamp.from(Instant.parse("2026-02-02T10:30:00Z")));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("minConnectedPercent", 75);
        rule.put("lateAfterMinutes", 10);
        rule.put("description", "Approved by the academy");
        rule.put("version", null);
        assertEquals(200, api.put("/api/v1/attendance-policy", HttpApi.json(rule), tokenFor(academyId, Role.ACADEMY_OWNER)).statusCode());
        assertEquals(200, api.post("/api/v1/class-sessions/" + session + "/attendance/finalisation", "{}", admin).statusCode());
        for (Map<String, Object> s : (List<Map<String, Object>>) HttpApi.parse(api.get("/api/v1/class-sessions/" + session + "/attendance", admin))
                .get("students")) {
            if (studentA.equals(s.get("studentId"))) {
                recordA = (String) s.get("recordId");
            } else if (studentB.equals(s.get("studentId"))) {
                recordB = (String) s.get("recordId");
            }
        }
    }

    private HttpResponse<String> correct(String record, String outcome, String reason, long version, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome);
        body.put("reason", reason);
        body.put("version", version);
        return api.post("/api/v1/attendance-records/" + record + "/corrections", HttpApi.json(body), token);
    }

    private HttpResponse<String> resolve(String appeal, String decision, String outcome, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("outcome", outcome);
        body.put("note", note);
        return api.post("/api/v1/attendance-appeals/" + appeal + "/resolution", HttpApi.json(body), admin);
    }

    @Test
    @SuppressWarnings("unchecked")
    void correctionsKeepAnAppendOnlyHistoryAndTheComputedOutcome() {
        HttpResponse<String> excused = correct(recordA, "EXCUSED", "Medical note received", 0, admin);
        assertEquals(200, excused.statusCode(), excused.body());
        assertEquals("EXCUSED", HttpApi.parse(excused).get("outcome"));
        assertEquals("ABSENT", HttpApi.parse(excused).get("computedOutcome"), "the rule's decision is kept");
        assertEquals(409, correct(recordA, "PRESENT", "Stale form", 0, admin).statusCode(), "version check");
        assertEquals(400, correct(recordA, "EXCUSED", "Same again", 1, admin).statusCode(), "no change");
        assertEquals(400, correct(recordA, "MAYBE", "Unknown", 1, admin).statusCode());
        assertEquals(400, correct(recordA, "PRESENT", "", 1, admin).statusCode());
        assertEquals(200, correct(recordA, "PRESENT", "Joined by phone; confirmed by the instructor", 1, admin).statusCode());

        List<Map<String, Object>> history = (List<Map<String, Object>>) (Object) HttpApi.parseList(
                api.get("/api/v1/attendance-records/" + recordA + "/corrections", admin));
        assertEquals(List.of("ABSENT>EXCUSED", "EXCUSED>PRESENT"),
                history.stream().map(c -> c.get("previousOutcome") + ">" + c.get("newOutcome")).toList());
        assertThrows(Exception.class, () -> jdbc.update("UPDATE attendance_corrections SET reason = 'x'"), "append-only");
        assertThrows(Exception.class, () -> jdbc.update("DELETE FROM attendance_corrections"), "append-only");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE academy_id = ? AND action = 'ATTENDANCE_CORRECTED'",
                Integer.class, academyId));
        // The class view and the student's history show the outcome in force.
        List<Object> staffHistory = HttpApi.parseList(api.get("/api/v1/students/" + studentA + "/attendance", admin));
        assertEquals("PRESENT", ((Map<String, Object>) staffHistory.get(0)).get("outcome"));
        assertEquals("Optics", ((Map<String, Object>) staffHistory.get(0)).get("classTitle"));
    }

    @Test
    void attendanceOfficersCorrectOnlyTheirBatchesStudents() {
        accountIdOf("officer.a@example.invalid", academyId, Role.ATTENDANCE_OFFICER);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("email", "officer.a@example.invalid");
        fields.put("role", "ATTENDANCE_OFFICER");
        assertEquals(201, api.post("/api/v1/batches/" + batchA + "/assignments", HttpApi.json(fields), admin).statusCode());
        String officer = login("officer.a@example.invalid");
        assertEquals(200, correct(recordA, "EXCUSED", "Medical note", 0, officer).statusCode());
        assertEquals(404, correct(recordB, "ABSENT", "Not their batch", 0, officer).statusCode());
        assertEquals(403, correct(recordA, "PRESENT", "Instructors do not correct", 1, tokenFor(academyId, Role.INSTRUCTOR)).statusCode());
        assertEquals(404, correct(recordA, "PRESENT", "Other academy", 1, tokenFor(academy("Other"), Role.ACADEMY_ADMIN)).statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStudentAppealsTheirOwnRecordAndStaffAcceptOrReject() {
        String student = login("corr.a@example.test");
        List<Object> mine = HttpApi.parseList(api.get("/api/v1/me/attendance", student));
        assertEquals(1, mine.size());
        assertEquals("ABSENT", ((Map<String, Object>) mine.get(0)).get("outcome"));
        assertNull(((Map<String, Object>) mine.get(0)).get("appealStatus"));

        String path = "/api/v1/me/attendance/" + recordA + "/appeals";
        assertEquals(400, api.post(path, "{\"message\":\"too short\"}", student).statusCode());
        HttpResponse<String> first = api.post(path, "{\"message\":\"I joined from my phone with my parent's Zoom account.\"}", student);
        assertEquals(201, first.statusCode(), first.body());
        String appeal = (String) HttpApi.parse(first).get("id");
        assertTrue(notices("A student appealed their attendance").stream().anyMatch(m -> m.link().contains("Optics on 2 Feb 2026")
                && m.link().contains("https://luma.example.invalid/staff/attendance")), "owners and administrators are told");
        assertEquals(409, api.post(path, "{\"message\":\"A second appeal while one is open.\"}", student).statusCode());
        assertEquals(404, api.post("/api/v1/me/attendance/" + recordB + "/appeals", "{\"message\":\"Not my record at all, but trying.\"}", student)
                .statusCode(), "only one's own record");
        assertEquals(403, api.get("/api/v1/attendance-appeals", student).statusCode());

        List<Object> open = HttpApi.parseList(api.get("/api/v1/attendance-appeals?status=OPEN", admin));
        assertEquals(1, open.size());
        assertEquals("S-001", ((Map<String, Object>) open.get(0)).get("studentNumber"));
        assertEquals(400, resolve(appeal, "ACCEPT", null, "Accepted").statusCode(), "accepting needs the new outcome");
        HttpResponse<String> accepted = resolve(appeal, "ACCEPT", "PRESENT", "Parent's account confirmed in Zoom review");
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals("ACCEPTED", HttpApi.parse(accepted).get("status"));
        assertEquals("PRESENT", HttpApi.parse(accepted).get("outcome"));
        java.util.List<com.physicscube.luma.identity.CapturedMail.Mail> updated = notices("Your attendance was updated");
        assertEquals(1, updated.size());
        assertEquals("corr.a@example.test", updated.get(0).to());
        assertTrue(updated.get(0).link().contains("is now Present (it was Absent)"), updated.get(0).link());
        assertEquals(409, resolve(appeal, "REJECT", null, "Too late").statusCode(), "decided once");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM attendance_corrections WHERE appeal_id = ?::uuid", Integer.class, appeal));

        // A second appeal can be rejected with a note the student sees.
        HttpResponse<String> again = api.post(path, "{\"message\":\"Please mark me late instead of present.\"}", student);
        assertEquals(201, again.statusCode());
        String second = (String) HttpApi.parse(again).get("id");
        assertEquals(200, resolve(second, "REJECT", null, "Present is the more favourable outcome").statusCode());
        assertTrue(notices("Your attendance appeal was not accepted").stream()
                .anyMatch(m -> m.link().contains("Present is the more favourable outcome")));
        Map<String, Object> latest = (Map<String, Object>) HttpApi.parseList(api.get("/api/v1/me/attendance", student)).get(0);
        assertEquals("PRESENT", latest.get("outcome"));
        assertEquals("REJECTED", latest.get("appealStatus"));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE academy_id = ? AND action LIKE 'ATTENDANCE_APPEAL_%'",
                Integer.class, academyId) == 4);
    }

    /** Notices sent after commit on the mail queue; waits briefly for them. */
    private static java.util.List<com.physicscube.luma.identity.CapturedMail.Mail> notices(String subject) {
        java.util.List<com.physicscube.luma.identity.CapturedMail.Mail> found = new java.util.ArrayList<>();
        long until = System.currentTimeMillis() + 5000;
        while (found.isEmpty() && System.currentTimeMillis() < until) {
            com.physicscube.luma.identity.CapturedMail.SENT.stream().filter(m -> m.kind().equals("notice:" + subject)).forEach(found::add);
            if (found.isEmpty()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return found;
    }
}
