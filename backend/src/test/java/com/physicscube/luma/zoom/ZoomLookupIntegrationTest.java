package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Check a webinar by link or ID, including runs LUMA never saw live. A fake Zoom answers; values are made up. */
class ZoomLookupIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "83812345678";
    private static final String RUN_OLD = "Old+Run==";
    private static final String RUN_NEW = "New/Run==";
    private static final HttpServer ZOOM;
    private static final AtomicInteger REPORT_CALLS = new AtomicInteger();

    static {
        try {
            ZOOM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ZOOM.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String target = exchange.getRequestMethod() + " " + path;
            int status = 404;
            String body = "{\"code\":3001,\"message\":\"Webinar does not exist\"}";
            if (target.equals("POST /oauth/token")) {
                status = 200;
                body = "{\"access_token\":\"fixture-access\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"webinar:read:webinar:admin\"}";
            } else if (target.equals("GET /v2/webinars/" + WEBINAR)) {
                status = 200;
                body = "{\"id\":" + WEBINAR + ",\"topic\":\"Physics revision\",\"type\":5,\"duration\":60,\"timezone\":\"Asia/Colombo\"}";
            } else if (target.equals("GET /v2/past_webinars/" + WEBINAR + "/instances")) {
                status = 200;
                body = "{\"webinars\":[{\"uuid\":\"" + RUN_OLD + "\",\"start_time\":\"2026-02-02T08:30:00Z\"},"
                        + "{\"uuid\":\"" + RUN_NEW + "\",\"start_time\":\"2026-02-09T08:30:00Z\"}]}";
            } else if (target.startsWith("GET /v2/past_webinars/") && target.endsWith("/participants")) {
                REPORT_CALLS.incrementAndGet();
                status = 200;
                body = "{\"next_page_token\":\"\",\"participants\":[" + String.join(",",
                        row("student1@example.test", "One", "08:31", "09:00"), row("Student1@Example.test", "One", "08:55", "09:40"),
                        row("guest@example.test", "Guest", "08:40", "08:50"), row(null, "Phone", "08:35", "08:45")) + "]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ZOOM.start();
    }

    private static String row(String email, String name, String join, String leave) {
        return "{\"id\":\"\",\"name\":\"" + name + "\"," + (email == null ? "" : "\"user_email\":\"" + email + "\",")
                + "\"join_time\":\"2026-02-02T" + join + ":00Z\",\"leave_time\":\"2026-02-02T" + leave + ":00Z\",\"duration\":60}";
    }

    @DynamicPropertySource
    static void zoomApi(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + ZOOM.getAddress().getPort();
        registry.add("luma.zoom.api.account-id", () -> "fake-account");
        registry.add("luma.zoom.api.client-id", () -> "fake-client");
        registry.add("luma.zoom.api.client-secret", () -> "fixture-client-value");
        registry.add("luma.zoom.api.token-url", () -> base + "/oauth/token");
        registry.add("luma.zoom.api.api-base-url", () -> base + "/v2");
    }

    UUID academyId;
    String admin;
    String batch;
    String student;

    @BeforeEach
    void seed() {
        resetDatabase();
        REPORT_CALLS.set(0);
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        student = (String) fixtures.student("S-001", "student1@example.test").get("id");
        assertEquals(201, fixtures.enroll(batch, student, "2026-01-01").statusCode());
    }

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> fetch(String webinar, String run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("webinar", webinar);
        body.put("occurrenceUuid", run);
        return api.post("/api/v1/zoom/webinar-lookup/reports", HttpApi.json(body), admin);
    }

    @Test
    void joinLinksAndTypedIdsAreRecognised() {
        assertEquals(WEBINAR, ZoomLookupService.webinarId("838 1234 5678"));
        assertEquals(WEBINAR, ZoomLookupService.webinarId("838-1234-5678"));
        assertEquals(WEBINAR, ZoomLookupService.webinarId("https://us06web.zoom.us/w/" + WEBINAR + "?tk=abc&uuid=x"));
        assertEquals(WEBINAR, ZoomLookupService.webinarId("https://zoom.us/j/" + WEBINAR + "?pwd=word"));
        assertEquals(WEBINAR, ZoomLookupService.webinarId("https://us06web.zoom.us/wc/join/" + WEBINAR));
        assertThrows(InvalidRequestException.class, () -> ZoomLookupService.webinarId("https://us06web.zoom.us/webinar/register/WN_abcdef"));
        assertThrows(InvalidRequestException.class, () -> ZoomLookupService.webinarId("not a webinar"));
        assertThrows(InvalidRequestException.class, () -> ZoomLookupService.webinarId("http://zoom.us/j/" + WEBINAR));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPastRunNeverSeenLiveIsCheckedFromZoomsReportAndLinkedToItsClass() {
        HttpResponse<String> found = api.get("/api/v1/zoom/webinar-lookup?webinar=" + q("https://us06web.zoom.us/w/" + WEBINAR + "?tk=x"), admin);
        assertEquals(200, found.statusCode(), found.body());
        Map<String, Object> lookup = HttpApi.parse(found);
        assertEquals("Physics revision", lookup.get("topic"));
        List<Map<String, Object>> runs = (List<Map<String, Object>>) lookup.get("runs");
        assertEquals(List.of(RUN_NEW, RUN_OLD), runs.stream().map(r -> r.get("occurrenceUuid")).toList(), "newest first");
        assertEquals(false, runs.get(1).get("reported"));

        assertEquals(404, api.get("/api/v1/zoom/webinar-lookup/attendance?webinar=" + WEBINAR + "&occurrence=" + q(RUN_OLD), admin).statusCode(),
                "not fetched yet");
        HttpResponse<String> fetched = fetch("838 1234 5678", RUN_OLD);
        assertEquals(200, fetched.statusCode(), fetched.body());
        Map<String, Object> attendance = HttpApi.parse(fetched);
        assertEquals(4, attendance.get("joinSessions"));
        List<Map<String, Object>> people = (List<Map<String, Object>>) attendance.get("attendees");
        assertEquals(3, people.size(), "the student's two sessions are one person, whatever the letter case");
        Map<String, Object> one = people.stream().filter(p -> "S-001".equals(p.get("studentNumber"))).findFirst().orElseThrow();
        assertEquals("STUDENT", one.get("match"));
        assertEquals(2, one.get("joinSessions"));
        assertEquals(4140, ((Number) one.get("connectedSeconds")).intValue(), "08:31–09:40 with an overlapping rejoin");
        assertEquals("UNMATCHED", people.get(0).get("match"), "unmatched people first");
        assertEquals(1, REPORT_CALLS.get());
        assertEquals(true, ((List<Map<String, Object>>) HttpApi.parse(api.get("/api/v1/zoom/webinar-lookup?webinar=" + WEBINAR, admin))
                .get("runs")).get(1).get("reported"));

        // Linking the run to its class makes it that class's attendance, from Zoom's report.
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Revision\",\"startsAt\":\"2026-02-02T08:30:00Z\","
                + "\"endsAt\":\"2026-02-02T10:30:00Z\",\"batchIds\":[\"" + batch + "\"]}", admin);
        String session = (String) HttpApi.parse(created).get("id");
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("webinar", WEBINAR);
        link.put("occurrenceUuid", RUN_OLD);
        link.put("sessionId", session);
        link.put("reason", "Checked from the webinar link");
        HttpResponse<String> linked = api.post("/api/v1/zoom/webinar-lookup/links", HttpApi.json(link), admin);
        assertEquals(200, linked.statusCode(), linked.body());
        assertEquals(session, HttpApi.parse(linked).get("linkedSessionId"));
        Map<String, Object> classView = HttpApi.parse(api.get("/api/v1/class-sessions/" + session + "/attendance", admin));
        assertEquals("RECONCILED", classView.get("status"));
        Map<String, Object> row = ((List<Map<String, Object>>) classView.get("students")).get(0);
        assertEquals(true, row.get("seen"));
        assertEquals(4140, ((Number) row.get("connectedSeconds")).intValue());
        assertEquals(List.of("lookup", "lookup"), jdbc.queryForList("SELECT details->>'via' FROM audit_events WHERE academy_id = ? "
                + "AND action IN ('ZOOM_REPORT_FETCHED', 'ZOOM_OCCURRENCE_LINKED') ORDER BY occurred_at", String.class, academyId));
    }

    @Test
    void unknownWebinarsRunsAndOtherRolesAreRefused() {
        assertEquals(404, api.get("/api/v1/zoom/webinar-lookup?webinar=99999999999", admin).statusCode());
        assertEquals(400, api.get("/api/v1/zoom/webinar-lookup?webinar=" + q("https://us06web.zoom.us/webinar/register/WN_x"), admin).statusCode());
        assertEquals(404, fetch(WEBINAR, "Some/Other==").statusCode(), "only a run of this webinar");
        assertEquals(0, REPORT_CALLS.get());
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("webinar", WEBINAR);
        link.put("occurrenceUuid", RUN_NEW);
        link.put("sessionId", UUID.randomUUID().toString());
        link.put("reason", "Too early");
        assertEquals(409, api.post("/api/v1/zoom/webinar-lookup/links", HttpApi.json(link), admin).statusCode(), "fetch the report first");
        String instructor = tokenFor(academyId, Role.INSTRUCTOR);
        assertEquals(403, api.get("/api/v1/zoom/webinar-lookup?webinar=" + WEBINAR, instructor).statusCode());
        assertNull(jdbc.query("SELECT 1 FROM zoom_report_fetches LIMIT 1", rs -> rs.next() ? 1 : null));
    }
}
