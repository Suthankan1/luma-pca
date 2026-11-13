package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import com.physicscube.luma.zoom.ZoomReceiptProcessor;
import com.physicscube.luma.zoom.ZoomTestReceipts;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Attendance CSV exports (005A): same facts and scoping as the attendance view; audited; safe in spreadsheets. */
class AttendanceExportIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;

    UUID academyId;
    String admin;
    String batchA;
    String batchB;
    String shared;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String course = fixtures.course("PHY-2026");
        batchA = fixtures.batch(course, "A");
        batchB = fixtures.batch(course, "B");
        String first = (String) fixtures.student("S-001", "student1@example.test").get("id");
        HttpResponse<String> formula = fixtures.postStudent("S-002", "=SUM(1,2)", "student2@example.test");
        assertEquals(201, formula.statusCode(), formula.body());
        String second = (String) HttpApi.parse(formula).get("id");
        String other = (String) fixtures.student("S-005", "student5@example.test").get("id");
        for (String id : List.of(first, second)) {
            assertEquals(201, fixtures.enroll(batchA, id, "2026-01-01").statusCode());
        }
        assertEquals(201, fixtures.enroll(batchB, other, "2026-01-01").statusCode());
        shared = session("Optics, shared", "2026-02-02T08:30:00Z", "2026-02-02T10:30:00Z", List.of(batchA, batchB), WEBINAR);
        session("Waves", "2026-02-09T08:30:00Z", "2026-02-09T10:30:00Z", List.of(batchA), null);
        session("Later", "2026-05-20T08:30:00Z", "2026-05-20T10:30:00Z", List.of(batchA), null);
        // Student 1 joins the shared class 10 minutes late and stays an hour.
        event("webinar.started", "2026-02-02T08:30:00Z", null);
        event("webinar.participant_joined", "2026-02-02T08:40:00Z",
                "{\"id\":\"\",\"participant_uuid\":\"v1\",\"email\":\"student1@example.test\"}");
        event("webinar.participant_left", "2026-02-02T09:40:00Z",
                "{\"id\":\"\",\"participant_uuid\":\"v1\",\"email\":\"student1@example.test\"}");
        processor.runOnce();
    }

    private String session(String title, String startsAt, String endsAt, List<String> batches, String webinar) {
        String json = "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\"" + endsAt + "\",\"batchIds\":[\""
                + String.join("\",\"", batches) + "\"]" + (webinar == null ? "" : ",\"zoomWebinarId\":\"" + webinar + "\"") + "}";
        HttpResponse<String> created = api.post("/api/v1/class-sessions", json, admin);
        assertEquals(201, created.statusCode(), created.body());
        return (String) HttpApi.parse(created).get("id");
    }

    private void event(String type, String at, String participant) {
        long ts = Instant.parse(at).toEpochMilli();
        String json = "{\"event\":\"" + type + "\",\"event_ts\":" + ts + ",\"payload\":{\"object\":{\"id\":\"" + WEBINAR
                + "\",\"uuid\":\"Shared==\",\"host_id\":\"hostuser\"" + (participant == null ? "" : ",\"participant\":" + participant) + "}}}";
        ZoomTestReceipts.store(context, academyId, type, ts, json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> lines(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return List.of(response.body().split("\r\n"));
    }

    @Test
    void aClassExportsOneRowPerStudentWithTheViewsFactsAsAPrivateDownload() {
        HttpResponse<String> response = api.get("/api/v1/class-sessions/" + shared + "/attendance/export", admin);
        List<String> lines = lines(response);
        assertEquals(AttendanceExportService.HEADER, lines.get(0));
        assertEquals(4, lines.size(), "three expected students from both batches");
        assertEquals("2026-02-02,14:00,16:00,Asia/Colombo,\"Optics, shared\",SCHEDULED,PROVISIONAL,S-001,Student S-001,yes,yes,1,0,60,"
                + "2026-02-02 14:10,10", lines.get(1));
        assertEquals("2026-02-02,14:00,16:00,Asia/Colombo,\"Optics, shared\",SCHEDULED,PROVISIONAL,S-002,\"'=SUM(1,2)\",yes,no,0,0,0,,",
                lines.get(2), "a name that looks like a formula is neutralised");
        assertTrue(lines.get(3).contains(",S-005,"));

        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        String disposition = response.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.startsWith("attachment;") && disposition.contains("attendance-2026-02-02-optics-shared.csv"), disposition);

        Map<String, Object> audit = jdbc.queryForMap("SELECT target_type, target_id::text AS target, details->>'rows' AS rows, "
                + "details->>'scope' AS scope FROM audit_events WHERE action = 'ATTENDANCE_EXPORTED' AND academy_id = ?", academyId);
        assertEquals("CLASS_SESSION", audit.get("target_type"));
        assertEquals(shared, audit.get("target"));
        assertEquals("3", audit.get("rows"));
    }

    @Test
    void aBatchExportCoversItsClassesInTheRangeAndOnlyItsStudents() {
        List<String> lines = lines(api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-02-01&to=2026-02-28", admin));
        assertEquals(5, lines.size(), "two classes in February, two students of batch A each; May is outside");
        assertTrue(lines.get(1).startsWith("2026-02-02,") && lines.get(1).contains(",S-001,"));
        assertTrue(lines.get(2).startsWith("2026-02-02,") && lines.get(2).contains(",S-002,"));
        assertTrue(lines.get(3).startsWith("2026-02-09,") && lines.get(3).contains(",Waves,"));
        assertTrue(lines.stream().noneMatch(l -> l.contains("S-005")), "batch B's student is not in batch A's export");

        assertEquals(400, api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-02-01&to=2026-05-04", admin).statusCode(),
                "93 days is too long");
        assertEquals(400, api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-02-10&to=2026-02-01", admin).statusCode());
        assertEquals(400, api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-02-01", admin).statusCode());
        assertEquals(200, api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-02-01&to=2026-05-03", admin).statusCode(),
                "92 days is allowed");
    }

    @Test
    void assignedStaffExportOnlyTheirBatchAndStudentsCannotExport() {
        accountIdOf("teacher.b@example.invalid", academyId, Role.INSTRUCTOR);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("email", "teacher.b@example.invalid");
        fields.put("role", "INSTRUCTOR");
        assertEquals(201, api.post("/api/v1/batches/" + batchB + "/assignments", HttpApi.json(fields), admin).statusCode());
        String teacher = login("teacher.b@example.invalid");

        List<String> own = lines(api.get("/api/v1/batches/" + batchB + "/attendance/export?from=2026-02-01&to=2026-02-28", teacher));
        assertEquals(2, own.size());
        assertTrue(own.get(1).contains(",S-005,"));
        List<String> sharedClass = lines(api.get("/api/v1/class-sessions/" + shared + "/attendance/export", teacher));
        assertEquals(2, sharedClass.size(), "a shared class shows the instructor only their batch's student");

        assertEquals(404, api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-02-01&to=2026-02-28", teacher).statusCode());
        String student = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, api.get("/api/v1/class-sessions/" + shared + "/attendance/export", student).statusCode());
        assertEquals(403, api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-02-01&to=2026-02-28", student).statusCode());
    }

    @Test
    void cellsAreQuotedAndFormulasNeutralised() {
        assertEquals("plain", AttendanceExportService.cell("plain"));
        assertEquals("\"a,b\"", AttendanceExportService.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", AttendanceExportService.cell("say \"hi\""));
        assertEquals("\"'+44 7700\"", AttendanceExportService.cell("+44 7700"));
        assertEquals("\"'@cmd\"", AttendanceExportService.cell("@cmd"));
        assertEquals("\"'-1\"", AttendanceExportService.cell("-1"));
        assertEquals("", AttendanceExportService.cell(null));
    }
}
