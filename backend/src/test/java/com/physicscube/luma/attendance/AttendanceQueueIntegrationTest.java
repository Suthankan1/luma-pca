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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Classes waiting to be finalised, finalising every ready one at once, and the owner's automatic finalisation. */
class AttendanceQueueIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234500001";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;
    @Autowired
    AttendanceQueueService queue;

    UUID academyId;
    UUID ownerId;
    String owner;
    String admin;
    String batch;
    String ready;
    String waiting;
    String unlinked;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        ownerId = accountIdOf("owner.q." + UUID.randomUUID() + "@example.invalid", academyId, Role.ACADEMY_OWNER);
        owner = login(jdbc.queryForObject("SELECT email FROM accounts WHERE id = ?", String.class, ownerId));
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        String student = (String) fixtures.student("S-001", "student1@example.test").get("id");
        assertEquals(201, fixtures.enroll(batch, student, "2026-01-01").statusCode());

        ready = session("Ready class", "2026-03-02T08:30:00Z", "2026-03-02T10:30:00Z", WEBINAR);
        run("Ready==", "2026-03-02T08:29:00Z", true);
        waiting = session("Waiting class", "2026-03-04T08:30:00Z", "2026-03-04T10:30:00Z", WEBINAR);
        run("Waiting==", "2026-03-04T08:29:00Z", false);
        unlinked = session("No Zoom class", "2026-03-06T08:30:00Z", "2026-03-06T10:30:00Z", null);
        session("Future class", "2099-03-06T08:30:00Z", "2099-03-06T10:30:00Z", WEBINAR);
    }

    private String session(String title, String startsAt, String endsAt, String webinar) {
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt
                + "\",\"endsAt\":\"" + endsAt + "\",\"batchIds\":[\"" + batch + "\"]" + (webinar == null ? "" : ",\"zoomWebinarId\":\"" + webinar + "\"")
                + "}", admin);
        assertEquals(201, created.statusCode(), created.body());
        String id = (String) HttpApi.parse(created).get("id");
        // Classes in the past, but recent enough for the queue: move them to within the last weeks, keeping their length.
        if (startsAt.startsWith("2026")) {
            jdbc.update("UPDATE class_sessions SET starts_at = starts_at + (now() - ?::timestamptz) - interval '10 days', "
                    + "ends_at = ends_at + (now() - ?::timestamptz) - interval '10 days' WHERE id = ?::uuid", "2026-03-02T00:00:00Z",
                    "2026-03-02T00:00:00Z", id);
        }
        return id;
    }

    /** Zoom: the run starts (linking it to its class); with {@code reported}, its participant report has arrived. */
    private void run(String occurrence, String startedAt, boolean reported) {
        Instant start = jdbc.queryForObject("SELECT ?::timestamptz + (now() - ?::timestamptz) - interval '10 days'", Timestamp.class,
                startedAt, "2026-03-02T00:00:00Z").toInstant();
        long ts = start.toEpochMilli();
        ZoomTestReceipts.store(context, academyId, "webinar.started", ts, ("{\"event\":\"webinar.started\",\"event_ts\":" + ts
                + ",\"payload\":{\"object\":{\"id\":\"" + WEBINAR + "\",\"uuid\":\"" + occurrence + "\",\"host_id\":\"h\"}}}")
                .getBytes(StandardCharsets.UTF_8));
        processor.runOnce();
        if (reported) {
            jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, fetched_at, join_sessions) "
                    + "VALUES (?, ?, 'FETCHED', 1, now(), 1)", academyId, occurrence);
            jdbc.update("INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, display_name, joined_at, left_at, "
                    + "duration_seconds) VALUES (?, ?, ?, 'student1@example.test', 'One', ?, ?, 0)", UUID.randomUUID(), academyId, occurrence,
                    Timestamp.from(start.plusSeconds(60)), Timestamp.from(start.plusSeconds(7260)));
        }
    }

    private void rule(Boolean auto, Long version) {
        HttpResponse<String> saved = api.put("/api/v1/attendance-policy", "{\"minConnectedPercent\":75,\"lateAfterMinutes\":10,"
                + "\"description\":\"Approved\",\"version\":" + version + (auto == null ? "" : ",\"autoFinalise\":" + auto) + "}", owner);
        assertEquals(200, saved.statusCode(), saved.body());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> states() {
        Map<String, Object> pending = HttpApi.parse(api.get("/api/v1/attendance/pending", admin));
        return ((List<Map<String, Object>>) pending.get("classes")).stream()
                .collect(Collectors.toMap(c -> (String) c.get("title"), c -> (String) c.get("state")));
    }

    @Test
    void thePendingListSaysWhatEachClassWaitsForAndFinalisingAllDoesOnlyTheReadyOnes() {
        assertEquals(Map.of("Ready class", "READY", "Waiting class", "WAITING_FOR_REPORT", "No Zoom class", "NO_ZOOM_RUN"), states(),
                "future classes are not listed");
        assertEquals(false, HttpApi.parse(api.get("/api/v1/attendance/pending", admin)).get("ruleRecorded"));
        assertEquals(409, api.post("/api/v1/attendance/finalisations", "{}", admin).statusCode(), "no rule yet");

        rule(null, null);
        HttpResponse<String> bulk = api.post("/api/v1/attendance/finalisations", "{}", admin);
        assertEquals(200, bulk.statusCode(), bulk.body());
        assertEquals(1, HttpApi.parse(bulk).get("finalised"));
        assertEquals(2, HttpApi.parse(bulk).get("notReady"));
        assertEquals("PRESENT", jdbc.queryForObject("SELECT outcome FROM attendance_records WHERE class_session_id = ?::uuid", String.class, ready));
        assertEquals(Map.of("Waiting class", "WAITING_FOR_REPORT", "No Zoom class", "NO_ZOOM_RUN"), states(), "final classes leave the list");

        assertEquals(403, api.get("/api/v1/attendance/pending", tokenFor(academyId, Role.INSTRUCTOR)).statusCode());
        assertEquals(403, api.post("/api/v1/attendance/finalisations", "{}", tokenFor(academyId, Role.ANALYST)).statusCode());
    }

    @Test
    void automaticFinalisationRunsOnlyWhenTheOwnerSwitchedItOnAndIsStillAnActiveOwner() {
        rule(null, null);
        assertEquals(0, queue.autoFinaliseOnce(), "off by default");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE academy_id = ?", Integer.class, academyId));

        assertEquals(403, api.put("/api/v1/attendance-policy", "{\"minConnectedPercent\":75,\"lateAfterMinutes\":10,\"description\":\"x y z\","
                + "\"version\":0,\"autoFinalise\":true}", admin).statusCode(), "only an owner decides");
        rule(true, 0L);
        assertEquals(true, HttpApi.parse(api.get("/api/v1/attendance-policy", admin)).get("autoFinalise"));
        assertEquals(1, queue.autoFinaliseOnce());
        assertEquals(ownerId, jdbc.queryForObject("SELECT finalised_by FROM attendance_records WHERE class_session_id = ?::uuid", UUID.class, ready));
        assertEquals("true", jdbc.queryForObject("SELECT details->>'automatic' FROM audit_events WHERE academy_id = ? AND action = "
                + "'ATTENDANCE_FINALISED'", String.class, academyId));
        assertEquals(0, queue.autoFinaliseOnce(), "nothing else is ready; never twice");

        // The report for the waiting class arrives later; it is finalised on the next run unless the owner left.
        jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, fetched_at, join_sessions) "
                + "VALUES (?, 'Waiting==', 'FETCHED', 1, now(), 0)", academyId);
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", ownerId);
        assertEquals(0, queue.autoFinaliseOnce(), "a disabled owner's switch does nothing");
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", ownerId);
        assertEquals(1, queue.autoFinaliseOnce());
        assertEquals("ABSENT", jdbc.queryForObject("SELECT outcome FROM attendance_records WHERE class_session_id = ?::uuid", String.class, waiting),
                "an empty report: the student was not there");
        assertTrue(states().containsKey("No Zoom class"), "a class with no Zoom run is never finalised automatically");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE class_session_id = ?::uuid", Integer.class, unlinked));
    }
}
