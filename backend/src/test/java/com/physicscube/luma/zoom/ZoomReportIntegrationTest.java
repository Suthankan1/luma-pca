package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Zoom's participant report replaces live events once fetched (004B). A fake Zoom answers on
 * localhost; the report's field names follow GET /past_webinars/{uuid}/participants. Values are made up.
 */
class ZoomReportIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";
    private static final String OCCURRENCE = "Report+One==";
    private static final HttpServer ZOOM;
    /** status, body, and Retry-After seconds (or null) of the next participant-report responses */
    private static final AtomicReference<Object[]> REPORT = new AtomicReference<>();
    private static final AtomicInteger REPORT_CALLS = new AtomicInteger();

    static {
        try {
            ZOOM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ZOOM.createContext("/", exchange -> {
            String target = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath();
            int status = 404;
            String body = "{\"code\":3001,\"message\":\"Not found\"}";
            if (target.equals("POST /oauth/token")) {
                status = 200;
                body = "{\"access_token\":\"fixture-access\",\"token_type\":\"bearer\",\"expires_in\":3600,"
                        + "\"scope\":\"webinar:read:list_past_participants:admin\"}";
            } else if (target.equals("GET /v2/webinars/" + WEBINAR)) {
                status = 200;
                body = "{\"id\":" + WEBINAR + ",\"topic\":\"Optics\",\"type\":5,\"host_id\":\"zoomhost\"}";
            } else if (target.equals("GET /v2/webinars/" + WEBINAR + "/registrants")) {
                status = 200;
                body = "{\"next_page_token\":\"\",\"registrants\":[{\"id\":\"reg-two\",\"email\":\"Student2@example.test\"}]}";
            } else if (target.startsWith("GET /v2/past_webinars/") && target.endsWith("/participants")) {
                REPORT_CALLS.incrementAndGet();
                Object[] reply = REPORT.get();
                status = (Integer) reply[0];
                body = (String) reply[1];
                if (reply[2] != null) {
                    exchange.getResponseHeaders().add("Retry-After", String.valueOf(reply[2]));
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ZOOM.start();
    }

    @DynamicPropertySource
    static void zoomApi(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + ZOOM.getAddress().getPort();
        registry.add("luma.zoom.api.account-id", () -> "fake-account");
        registry.add("luma.zoom.api.client-id", () -> "fake-client");
        registry.add("luma.zoom.api.client-secret", () -> "fixture-client-value");
        registry.add("luma.zoom.api.token-url", () -> base + "/oauth/token");
        registry.add("luma.zoom.api.api-base-url", () -> base + "/v2");
        registry.add("luma.zoom.reports.delay-after-end", () -> "PT0S");
    }

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ZoomWebhookReceipts receipts;
    @Autowired
    ZoomReportService reports;

    UUID academyId;
    String admin;
    String session;

    @BeforeEach
    void seed() {
        resetDatabase();
        REPORT_CALLS.set(0);
        REPORT.set(new Object[] {200, report(), null});
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        for (String n : List.of("1", "2")) {
            String id = (String) fixtures.student("S-00" + n, "student" + n + "@example.test").get("id");
            assertEquals(201, fixtures.enroll(batch, id, "2026-01-01").statusCode());
        }
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Optics\",\"startsAt\":\"2026-02-02T08:30:00Z\","
                + "\"endsAt\":\"2026-02-02T10:30:00Z\",\"batchIds\":[\"" + batch + "\"],\"zoomWebinarId\":\"" + WEBINAR + "\"}", admin);
        assertEquals(201, created.statusCode(), created.body());
        session = (String) HttpApi.parse(created).get("id");
    }

    private static String row(String email, String name, String join, String leave, int duration) {
        return "{\"id\":\"\",\"user_id\":\"16778240\",\"name\":\"" + name + "\"," + (email == null ? "" : "\"user_email\":\"" + email + "\",")
                + "\"join_time\":\"2026-02-02T" + join + ":00Z\",\"leave_time\":\"2026-02-02T" + leave + ":00Z\",\"duration\":" + duration
                + ",\"registrant_id\":\"reg\",\"status\":\"in_meeting\"}";
    }

    /** Student 1 in two sessions, student 2 (never seen live), the host, a guest and someone without email. */
    private static String report() {
        return "{\"page_size\":300,\"total_records\":6,\"next_page_token\":\"\",\"participants\":["
                + String.join(",", row("Student1@Example.test", "One", "08:35", "09:00", 1500), row("student1@example.test", "One", "09:05", "09:50", 2700),
                row("student2@example.test", "Two", "08:31", "09:00", 1740), row("teacher@example.test", "Teacher", "08:25", "10:00", 5700),
                row("guest@example.test", "Guest", "08:40", "08:50", 600), row(null, "Phone caller", "08:45", "09:15", 1800)) + "]}";
    }

    private void event(String type, String at, String participant) {
        long ts = Instant.parse("2026-02-02T" + at + ":00Z").toEpochMilli();
        String json = "{\"event\":\"" + type + "\",\"event_ts\":" + ts + ",\"payload\":{\"object\":{\"id\":\"" + WEBINAR + "\",\"uuid\":\""
                + OCCURRENCE + "\",\"host_id\":\"hostuser\"" + (participant == null ? "" : ",\"participant\":" + participant) + "}}}";
        receipts.store(academyId, type, ts, json.getBytes(StandardCharsets.UTF_8));
    }

    private void liveClass(boolean ended) {
        event("webinar.started", "08:25", null);
        event("webinar.participant_joined", "08:25", "{\"id\":\"hostuser\",\"participant_uuid\":\"h\",\"email\":\"teacher@example.test\"}");
        event("webinar.participant_joined", "08:40", "{\"id\":\"\",\"participant_uuid\":\"s1\",\"email\":\"student1@example.test\"}");
        if (ended) {
            event("webinar.ended", "10:00", null);
        }
        processor.runOnce();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attendance() {
        HttpResponse<String> response = api.get("/api/v1/class-sessions/" + session + "/attendance", admin);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> student(Map<String, Object> attendance, String number) {
        return ((List<Map<String, Object>>) attendance.get("students")).stream().filter(s -> number.equals(s.get("studentNumber")))
                .findFirst().orElseThrow();
    }

    private static long seconds(Map<String, Object> row) {
        return ((Number) row.get("connectedSeconds")).longValue();
    }

    private HttpResponse<String> fetchNow() {
        return api.post("/api/v1/zoom/reports", "{\"occurrenceUuid\":\"" + OCCURRENCE + "\"}", admin);
    }

    @Test
    void theReportReplacesLiveEventsOnceTheWebinarHasEnded() {
        liveClass(true);
        Map<String, Object> live = attendance();
        assertEquals("PROVISIONAL", live.get("status"));
        assertEquals(4800, seconds(student(live, "S-001")), "live: 08:40 to the webinar end at 10:00");
        assertEquals(false, student(live, "S-002").get("seen"));

        assertEquals(1, reports.runOnce(), "an ended, linked occurrence without a report is due");
        assertEquals(1, REPORT_CALLS.get());
        Map<String, Object> reconciled = attendance();
        assertEquals("RECONCILED", reconciled.get("status"));
        assertEquals(1, reconciled.get("reportedOccurrences"));
        Map<String, Object> first = student(reconciled, "S-001");
        assertEquals(4200, seconds(first), "the report's two sessions, 25 + 45 minutes");
        assertEquals(2, first.get("visits"));
        assertEquals(0, first.get("openVisits"));
        assertEquals(300, ((Number) first.get("secondsAfterStart")).intValue());
        Map<String, Object> second = student(reconciled, "S-002");
        assertEquals(true, second.get("seen"), "the report found a student the live events missed");
        assertEquals(1740, seconds(second));
        assertEquals(1, reconciled.get("unmatchedPeople"), "the guest; not the host, not the caller without email");

        assertEquals(0, reports.runOnce(), "a fetched report is not fetched again automatically");
        assertEquals(1, REPORT_CALLS.get());

        // Stored rows keep no IP or device fields, and the occurrence shows the report.
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM zoom_report_participants", Integer.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> occurrence = (Map<String, Object>) HttpApi.parseList(api.get("/api/v1/zoom/occurrences", admin)).get(0);
        assertEquals("FETCHED", occurrence.get("reportStatus"));
        assertEquals(6, occurrence.get("reportJoinSessions"));
        assertEquals(true, occurrence.get("ended"));
    }

    @Test
    void staffCanFetchAgainAndAFailureNeverDiscardsAnEarlierReport() {
        liveClass(true);
        HttpResponse<String> fetched = fetchNow();
        assertEquals(200, fetched.statusCode(), fetched.body());
        assertEquals("FETCHED", HttpApi.parse(fetched).get("status"));
        assertEquals(6, HttpApi.parse(fetched).get("joinSessions"));

        REPORT.set(new Object[] {500, "{\"code\":500,\"message\":\"Internal error\"}", null});
        HttpResponse<String> failed = fetchNow();
        assertEquals(200, failed.statusCode(), failed.body());
        assertEquals("FAILED", HttpApi.parse(failed).get("status"));
        assertEquals("RECONCILED", attendance().get("status"), "the earlier report is kept");
        assertEquals("FETCHED", jdbc.queryForObject("SELECT status FROM zoom_report_fetches", String.class));

        List<String> audit = jdbc.queryForList("SELECT details->>'status' FROM audit_events WHERE action = 'ZOOM_REPORT_FETCHED' "
                + "ORDER BY occurred_at", String.class);
        assertEquals(List.of("FETCHED", "FAILED"), audit);
    }

    @Test
    void failuresAreRetriedLaterHonouringRetryAfter() {
        liveClass(true);
        REPORT.set(new Object[] {429, "{\"code\":429,\"message\":\"Too many requests\"}", 36_000});
        assertEquals(1, reports.runOnce());
        Map<String, Object> state = jdbc.queryForMap("SELECT status, attempts, last_error, next_attempt_at FROM zoom_report_fetches");
        assertEquals("FAILED", state.get("status"));
        assertEquals(1, state.get("attempts"));
        assertTrue(((java.sql.Timestamp) state.get("next_attempt_at")).toInstant().isAfter(Instant.now().plusSeconds(35_000)),
                "Retry-After of 10 hours is longer than the 15-minute backoff");
        assertTrue(String.valueOf(state.get("last_error")).contains("429"), String.valueOf(state.get("last_error")));
        assertEquals(0, reports.runOnce(), "not due yet");
        assertEquals("PROVISIONAL", attendance().get("status"));
        assertNull(student(attendance(), "S-002").get("firstJoinAt"));
    }

    @Test
    void reportsAreOnlyFetchedForEndedOccurrencesByStaff() {
        liveClass(false);
        assertEquals(0, reports.runOnce(), "not ended: Zoom has no report yet");
        assertEquals(409, fetchNow().statusCode());
        assertEquals(404, api.post("/api/v1/zoom/reports", "{\"occurrenceUuid\":\"unknown\"}", admin).statusCode());
        String nonStaff = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, api.post("/api/v1/zoom/reports", "{\"occurrenceUuid\":\"" + OCCURRENCE + "\"}", nonStaff).statusCode());
        assertEquals(0, REPORT_CALLS.get());
    }

    /** A run never seen live: the host comes from Zoom's webinar details, a student is matched by registration email. */
    @Test
    @SuppressWarnings("unchecked")
    void aReportOnlyRunRecognisesTheHostMatchesByRegistrationEmailAndIsListed() {
        REPORT.set(new Object[] {200, "{\"next_page_token\":\"\",\"participants\":["
                + "{\"id\":\"zoomhost\",\"name\":\"Teacher\",\"user_email\":\"teacher.solo@example.test\",\"join_time\":\"2026-02-02T08:25:00Z\","
                + "\"leave_time\":\"2026-02-02T10:30:00Z\",\"duration\":7500},"
                + "{\"id\":\"\",\"name\":\"Two\",\"user_email\":\"other.account@example.test\",\"registrant_id\":\"reg-two\","
                + "\"join_time\":\"2026-02-02T08:30:00Z\",\"leave_time\":\"2026-02-02T10:30:00Z\",\"duration\":7200}]}", null});
        assertEquals("FETCHED", reports.fetch(academyId, "Solo==", WEBINAR).status());
        jdbc.update("INSERT INTO zoom_occurrence_links (academy_id, occurrence_uuid, webinar_id, class_session_id, link_source, linked_by, linked_at) "
                + "VALUES (?, 'Solo==', ?, ?::uuid, 'MANUAL', ?, now())", academyId, WEBINAR, session, UUID.randomUUID());

        Map<String, Object> view = attendance();
        assertEquals("RECONCILED", view.get("status"));
        assertEquals(0, view.get("unmatchedPeople"), "the host is recognised and not counted");
        Map<String, Object> second = student(view, "S-002");
        assertEquals(true, second.get("seen"), "joined from another Zoom account; registered with the email LUMA knows");
        assertEquals(7200, seconds(second));

        List<Map<String, Object>> listed = (List<Map<String, Object>>) (Object) HttpApi.parseList(api.get("/api/v1/zoom/occurrences?sessionId=" + session, admin));
        Map<String, Object> run = listed.stream().filter(o -> "Solo==".equals(o.get("occurrenceUuid"))).findFirst().orElseThrow();
        assertEquals(0, run.get("eventCount"), "listed although Zoom never sent live events for it");
        assertEquals("FETCHED", run.get("reportStatus"));
        assertEquals(WEBINAR, run.get("webinarId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void afterSixFailedAttemptsTheRunSaysItGaveUp() {
        liveClass(true);
        jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, next_attempt_at, last_error) "
                + "VALUES (?, ?, 'FAILED', 6, now(), 'Zoom 500')", academyId, OCCURRENCE);
        assertEquals(0, reports.runOnce(), "no more automatic attempts");
        Map<String, Object> occurrence = (Map<String, Object>) HttpApi.parseList(api.get("/api/v1/zoom/occurrences", admin)).get(0);
        assertEquals("GAVE_UP", occurrence.get("reportStatus"));
    }
}
