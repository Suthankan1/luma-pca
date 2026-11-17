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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Turning the academy's upcoming Zoom webinars into classes. A fake Zoom answers; values are made up. */
class ZoomImportIntegrationTest extends StaffApiIntegrationTest {
    private static final String SINGLE = "83800000001";
    private static final String WEEKLY = "83800000002";
    private static final String FLOATING = "83800000003";
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.DAYS);
    private static final HttpServer ZOOM;

    static {
        try {
            ZOOM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ZOOM.createContext("/", exchange -> {
            String target = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            int status = 404;
            String body = "{\"code\":3001,\"message\":\"Webinar does not exist\"}";
            if (target.equals("POST /oauth/token")) {
                status = 200;
                body = "{\"access_token\":\"fixture-access\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"webinar:read:list_webinars:admin\"}";
            } else if (target.equals("GET /v2/users/me/webinars") && query != null && query.contains("type=upcoming")) {
                status = 200;
                body = "{\"next_page_token\":\"\",\"webinars\":[" + listed(WEEKLY, 9) + "," + listed(SINGLE, 5) + "," + listed(FLOATING, 6) + "]}";
            } else if (target.equals("GET /v2/webinars/" + SINGLE)) {
                status = 200;
                body = "{\"id\":" + SINGLE + ",\"topic\":\"Revision day\",\"type\":5,\"start_time\":\"" + BASE + "\",\"duration\":90,"
                        + "\"timezone\":\"Asia/Colombo\",\"host_id\":\"host\"}";
            } else if (target.equals("GET /v2/webinars/" + WEEKLY)) {
                status = 200;
                body = "{\"id\":" + WEEKLY + ",\"topic\":\"A/L Physics weekly\",\"type\":9,\"duration\":120,\"timezone\":\"Asia/Colombo\","
                        + "\"host_id\":\"host\",\"occurrences\":["
                        + occurrence("1001", BASE.minus(7, ChronoUnit.DAYS), "available") + ","   // already over
                        + occurrence("1002", BASE.plus(1, ChronoUnit.DAYS), "available") + ","
                        + occurrence("1003", BASE.plus(8, ChronoUnit.DAYS), "available") + ","
                        + occurrence("1004", BASE.plus(15, ChronoUnit.DAYS), "deleted") + ","
                        + occurrence("1005", BASE.plus(22, ChronoUnit.DAYS), "available") + "]}";
            } else if (target.equals("GET /v2/webinars/" + FLOATING)) {
                status = 200;
                body = "{\"id\":" + FLOATING + ",\"topic\":\"Office hours\",\"type\":6,\"duration\":60,\"timezone\":\"Asia/Colombo\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ZOOM.start();
    }

    private static String listed(String id, int type) {
        return "{\"id\":" + id + ",\"topic\":\"Listed\",\"type\":" + type + ",\"start_time\":\"" + BASE + "\",\"duration\":60}";
    }

    private static String occurrence(String id, Instant start, String status) {
        return "{\"occurrence_id\":\"" + id + "\",\"start_time\":\"" + start + "\",\"duration\":120,\"status\":\"" + status + "\"}";
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

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> webinar(List<Object> upcoming, String id) {
        return upcoming.stream().map(o -> (Map<String, Object>) o).filter(w -> id.equals(w.get("webinarId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> runs(Map<String, Object> webinar) {
        return (List<Map<String, Object>>) webinar.get("runs");
    }

    private HttpResponse<String> importRuns(String webinarId, String extra) {
        return api.post("/api/v1/zoom/webinar-imports", "{\"webinarId\":\"" + webinarId + "\",\"batchIds\":[\"" + batch + "\"]" + extra + "}", admin);
    }

    @Test
    void upcomingWebinarsListEveryFutureRunAndMarkTheOnesAlreadyScheduled() {
        // One run already has a class, scheduled by hand with the webinar ID.
        HttpResponse<String> manual = api.post("/api/v1/class-sessions", "{\"title\":\"Week 1\",\"startsAt\":\"" + BASE.plus(1, ChronoUnit.DAYS)
                + "\",\"endsAt\":\"" + BASE.plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS) + "\",\"batchIds\":[\"" + batch
                + "\"],\"zoomWebinarId\":\"838 0000 0002\"}", admin);
        assertEquals(201, manual.statusCode(), manual.body());
        String manualId = (String) HttpApi.parse(manual).get("id");

        HttpResponse<String> response = api.get("/api/v1/zoom/upcoming-webinars", admin);
        assertEquals(200, response.statusCode(), response.body());
        List<Object> upcoming = HttpApi.parseList(response);
        assertEquals(3, upcoming.size());

        Map<String, Object> weekly = webinar(upcoming, WEEKLY);
        assertEquals("A/L Physics weekly", weekly.get("topic"));
        assertEquals(true, weekly.get("recurring"));
        assertEquals(true, weekly.get("liveEvents"), "no allow-list: every webinar's live events are kept");
        assertEquals(List.of("1002", "1003", "1005"), runs(weekly).stream().map(r -> r.get("occurrenceId")).toList(),
                "past and deleted runs are left out");
        assertEquals(manualId, runs(weekly).get(0).get("classId"));
        assertNull(runs(weekly).get(1).get("classId"));

        Map<String, Object> single = webinar(upcoming, SINGLE);
        assertEquals(1, runs(single).size());
        assertEquals(BASE.plus(90, ChronoUnit.MINUTES).toString(), runs(single).get(0).get("endsAt"));

        Map<String, Object> floating = webinar(upcoming, FLOATING);
        assertTrue(runs(floating).isEmpty());
        assertTrue(((String) floating.get("note")).contains("no fixed time"));
        assertEquals(SINGLE, ((Map<String, Object>) upcoming.get(0)).get("webinarId"), "soonest first");
    }

    @Test
    @SuppressWarnings("unchecked")
    void importingCreatesOneClassPerRunOnceAndOnlyForChosenRuns() {
        HttpResponse<String> some = importRuns(WEEKLY, ",\"occurrenceIds\":[\"1003\"],\"title\":\"Physics week\"");
        assertEquals(201, some.statusCode(), some.body());
        assertEquals(1, HttpApi.parse(some).get("created"));

        HttpResponse<String> rest = importRuns(WEEKLY, "");
        assertEquals(201, rest.statusCode(), rest.body());
        assertEquals(2, HttpApi.parse(rest).get("created"));
        assertEquals(1, HttpApi.parse(rest).get("alreadyScheduled"));
        Map<String, Object> again = HttpApi.parse(importRuns(WEEKLY, ""));
        assertEquals(0, again.get("created"), "importing twice never doubles a class");
        assertEquals(3, again.get("alreadyScheduled"));

        List<Map<String, Object>> classes = jdbc.queryForList("SELECT title, starts_at, ends_at, time_zone, zoom_webinar_id FROM class_sessions "
                + "WHERE academy_id = ? ORDER BY starts_at", academyId);
        assertEquals(3, classes.size());
        assertEquals("A/L Physics weekly", classes.get(0).get("title"), "Zoom's topic unless staff give a title");
        assertEquals("Physics week", classes.get(1).get("title"));
        assertEquals(WEEKLY, classes.get(0).get("zoom_webinar_id"));
        assertEquals("Asia/Colombo", classes.get(0).get("time_zone"));
        assertEquals(BASE.plus(1, ChronoUnit.DAYS), ((java.sql.Timestamp) classes.get(0).get("starts_at")).toInstant());
        assertEquals(BASE.plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS), ((java.sql.Timestamp) classes.get(0).get("ends_at")).toInstant());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM session_batches WHERE batch_id = ?::uuid", Integer.class, batch));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE academy_id = ? AND action = 'ZOOM_WEBINAR_IMPORTED'",
                Integer.class, academyId));
        Map<String, Object> listed = webinar(HttpApi.parseList(api.get("/api/v1/zoom/upcoming-webinars", admin)), WEEKLY);
        assertTrue(runs(listed).stream().allMatch(r -> r.get("classId") != null), "every run is now a class");
    }

    @Test
    void refusalsAreClear() {
        assertEquals(201, importRuns(SINGLE, "").statusCode());
        HttpResponse<String> floating = importRuns(FLOATING, "");
        assertEquals(400, floating.statusCode());
        assertTrue(floating.body().contains("no fixed time"), floating.body());
        assertEquals(404, importRuns("83899999999", "").statusCode(), "not in the academy's Zoom account");
        assertEquals(400, api.post("/api/v1/zoom/webinar-imports", "{\"webinarId\":\"" + WEEKLY + "\",\"batchIds\":[]}", admin).statusCode());
        assertEquals(400, importRuns("not-an-id", "").statusCode());
        AcademyFixtures other = new AcademyFixtures(api, tokenFor(academy("Other"), Role.ACADEMY_ADMIN));
        String otherBatch = other.batch(other.course("OTHER-1"), "Z");
        HttpResponse<String> foreign = api.post("/api/v1/zoom/webinar-imports", "{\"webinarId\":\"" + WEEKLY + "\",\"batchIds\":[\""
                + otherBatch + "\"]}", admin);
        assertTrue(foreign.statusCode() == 400 || foreign.statusCode() == 404, "another academy's batch: " + foreign.statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM class_sessions WHERE academy_id = ?", Integer.class, academyId),
                "nothing created by refused imports");

        String instructor = tokenFor(academyId, Role.INSTRUCTOR);
        assertEquals(403, api.get("/api/v1/zoom/upcoming-webinars", instructor).statusCode());
        assertEquals(403, api.post("/api/v1/zoom/webinar-imports", "{\"webinarId\":\"" + WEEKLY + "\",\"batchIds\":[\"" + batch + "\"]}",
                instructor).statusCode());
    }
}
