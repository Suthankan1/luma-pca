package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Academy decision 2026-11-18: staff give only a webinar ID or link; Zoom owns the schedule; every actual run Zoom starts
 * (early, late or unscheduled) becomes its own attendance run keyed by occurrence UUID. A fake Zoom API answers Get a
 * Webinar; webhooks are stored as the ingress stores them and processed by the real worker.
 */
class WebinarMonitorIntegrationTest extends StaffApiIntegrationTest {
    private static final String SCHEDULED = "84400000101";
    private static final String UNSCHEDULED = "84400000102";
    private static final String PHYSICS = "A/L Physics 2027";
    /** Zoom's scheduled start: 18:00 two days ago (UTC hours stand in for any zone). */
    private static final Instant SCHEDULED_AT = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(2, ChronoUnit.DAYS).plus(18, ChronoUnit.HOURS);
    private static final AtomicReference<String> SCHEDULED_TOPIC = new AtomicReference<>(PHYSICS);
    private static final AtomicReference<Instant> SCHEDULED_START = new AtomicReference<>(SCHEDULED_AT);
    /** A run that started 14 hours ago (longer than any class) and whose end Zoom never sent. */
    private static final Instant REPORT_START = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(14, ChronoUnit.HOURS);
    private static final HttpServer ZOOM;
    private static JdbcTemplate source;

    static {
        try {
            ZOOM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ZOOM.createContext("/", exchange -> {
            String target = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath();
            int status = 404;
            String body = "{\"code\":3001,\"message\":\"Webinar does not exist\"}";
            if (target.equals("POST /oauth/token")) {
                status = 200;
                body = "{\"access_token\":\"fixture-access\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"webinar:read:webinar:admin\"}";
            } else if (target.equals("GET /v2/webinars/" + SCHEDULED)) {
                status = 200;
                body = "{\"id\":" + SCHEDULED + ",\"topic\":\"" + SCHEDULED_TOPIC.get() + "\",\"type\":5,\"start_time\":\"" + SCHEDULED_START.get()
                        + "\",\"duration\":60,\"timezone\":\"Asia/Colombo\",\"host_id\":\"host\"}";
            } else if (target.startsWith("GET /v2/past_webinars/") && target.endsWith("/participants")) {
                status = 200;
                body = "{\"next_page_token\":\"\",\"participants\":[{\"id\":\"p1\",\"user_id\":\"u1\",\"name\":\"Amaya\","
                        + "\"user_email\":\"amaya@example.test\",\"join_time\":\"" + REPORT_START.plus(5, ChronoUnit.MINUTES)
                        + "\",\"leave_time\":\"" + REPORT_START.plus(65, ChronoUnit.MINUTES) + "\",\"duration\":3600,\"status\":\"in_meeting\"}]}";
            } else if (target.equals("GET /v2/webinars/" + UNSCHEDULED)) {
                status = 200;
                body = "{\"id\":" + UNSCHEDULED + ",\"topic\":\"Office hours\",\"type\":6,\"duration\":60,\"timezone\":\"Asia/Colombo\",\"host_id\":\"host\"}";
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
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + ZOOM.getAddress().getPort();
        registry.add("luma.zoom.api.account-id", () -> "fake-account");
        registry.add("luma.zoom.api.client-id", () -> "fake-client");
        registry.add("luma.zoom.api.client-secret", () -> "fixture-client-value");
        registry.add("luma.zoom.api.token-url", () -> base + "/oauth/token");
        registry.add("luma.zoom.api.api-base-url", () -> base + "/v2");
        registry.add("luma.zoom.monitors.sync-initial-delay", () -> "PT10H");
        registry.add("luma.academy-payments.enabled", () -> "true");
        registry.add("luma.academy-payments.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("luma.academy-payments.username", () -> "academy_wid_reader");
        registry.add("luma.academy-payments.password", () -> "quiet hill reader words");
        registry.add("luma.academy-payments.payment-table", () -> "academy_wid.payment");
        registry.add("luma.academy-payments.student-table", () -> "academy_wid.student");
    }

    @BeforeAll
    static void academySource() {
        source = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        source.execute("DROP SCHEMA IF EXISTS academy_wid CASCADE");
        source.execute("CREATE SCHEMA academy_wid");
        source.execute("CREATE TABLE academy_wid.payment (pcaid TEXT, type TEXT, class_type TEXT, deleted_at TIMESTAMPTZ)");
        source.execute("CREATE TABLE academy_wid.student (pcaid TEXT, name TEXT, mail TEXT, deleted_at TIMESTAMPTZ)");
        source.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'academy_wid_reader') THEN "
                + "CREATE ROLE academy_wid_reader LOGIN PASSWORD 'quiet hill reader words'; END IF; END $$");
        source.execute("GRANT USAGE ON SCHEMA academy_wid TO academy_wid_reader");
        source.execute("GRANT SELECT (pcaid, type, class_type, deleted_at) ON academy_wid.payment TO academy_wid_reader");
        source.execute("GRANT SELECT (pcaid, name, mail, deleted_at) ON academy_wid.student TO academy_wid_reader");
        source.update("INSERT INTO academy_wid.payment VALUES ('PCA001', 'class', ?, NULL), ('PCA002', 'class', ?, NULL), "
                + "('PCA003', 'class', 'Physics revision', NULL), ('PCA003', 'class', 'physics  REVISION ', NULL)", PHYSICS, PHYSICS);
        source.update("INSERT INTO academy_wid.student VALUES ('PCA001', 'Amaya Perera', 'amaya@example.test', NULL), "
                + "('PCA002', 'Kavin Silva', 'kavin@example.test', NULL)");
    }

    @AfterAll
    static void dropSource() {
        source.execute("DROP SCHEMA IF EXISTS academy_wid CASCADE");
        try {
            source.execute("DROP OWNED BY academy_wid_reader");
            source.execute("DROP ROLE IF EXISTS academy_wid_reader");
        } catch (org.springframework.dao.DataAccessException ignored) {
            // A connection still open as the reader keeps the role; it can read nothing now.
        }
    }

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ZoomReportService reports;
    @Autowired
    ApplicationContext context;

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batch;

    @BeforeEach
    void seed() {
        SCHEDULED_TOPIC.set(PHYSICS);
        SCHEDULED_START.set(SCHEDULED_AT);
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "MON");
    }

    private Map<String, Object> add(String input) {
        HttpResponse<String> response = api.post("/api/v1/zoom/monitors", HttpApi.json(Map.of("webinar", input)), admin);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private void event(String type, String webinarId, String uuid, Instant at, String extra) {
        long ts = at.toEpochMilli();
        String body = "{\"event\":\"" + type + "\",\"event_ts\":" + ts + ",\"payload\":{\"account_id\":\"acc\",\"object\":{\"id\":\"" + webinarId
                + "\",\"uuid\":\"" + uuid + "\",\"host_id\":\"host\",\"topic\":\"Zoom topic\"" + extra + "}}}";
        ZoomTestReceipts.store(context, academyId, type, ts, body.getBytes(StandardCharsets.UTF_8));
    }

    private void started(String webinarId, String uuid, Instant at) {
        event("webinar.started", webinarId, uuid, at, ",\"start_time\":\"" + at + "\"");
    }

    private void ended(String webinarId, String uuid, Instant start, Instant at) {
        event("webinar.ended", webinarId, uuid, at, ",\"start_time\":\"" + start + "\",\"end_time\":\"" + at + "\"");
    }

    private void visit(String webinarId, String uuid, String email, String visitKey, Instant joined, Instant left) {
        event("webinar.participant_joined", webinarId, uuid, joined, ",\"participant\":{\"user_id\":\"" + visitKey + "\",\"participant_uuid\":\""
                + visitKey + "\",\"user_name\":\"Someone\",\"email\":\"" + email + "\",\"join_time\":\"" + joined + "\"}");
        if (left != null) {
            event("webinar.participant_left", webinarId, uuid, left, ",\"participant\":{\"user_id\":\"" + visitKey + "\",\"participant_uuid\":\""
                    + visitKey + "\",\"user_name\":\"Someone\",\"email\":\"" + email + "\",\"leave_time\":\"" + left + "\"}");
        }
    }

    private void process() {
        while (processor.runOnce() > 0) {
            // drain
        }
    }

    private Map<String, Object> run(String uuid) {
        return jdbc.queryForMap("SELECT * FROM zoom_webinar_runs WHERE academy_id = ? AND occurrence_uuid = ?", academyId, uuid);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attendanceOf(Object sessionId, String studentNumber) {
        Map<String, Object> attendance = HttpApi.parse(api.get("/api/v1/class-sessions/" + sessionId + "/attendance", admin));
        return ((List<Map<String, Object>>) attendance.get("students")).stream()
                .filter(s -> studentNumber.equals(s.get("studentNumber"))).findFirst().orElse(null);
    }

    private void linkAcademyClass() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("className", PHYSICS);
        body.put("batchId", batch);
        assertEquals(200, api.put("/api/v1/academy/classes/link", HttpApi.json(body), admin).statusCode());
        assertEquals(200, api.post("/api/v1/academy/classes/sync", HttpApi.json(Map.of("className", PHYSICS)), admin).statusCode());
        // The test's runs are dated in the past; the sync enrols from today.
        jdbc.update("UPDATE enrollments SET enrolled_on = DATE '2026-01-01' WHERE academy_id = ?", academyId);
    }

    @Test
    void onlyAnIdOrLinkIsNeededAndTitleAndScheduleComeFromZoom() {
        Map<String, Object> byId = add("844 0000 0101");
        assertEquals(SCHEDULED, byId.get("webinarId"));
        assertEquals(PHYSICS, byId.get("topic"));
        assertEquals(SCHEDULED_AT.toString(), Instant.parse((String) byId.get("scheduledStartAt")).toString());
        assertEquals(60, byId.get("durationMinutes"));
        assertEquals("OK", byId.get("syncStatus"));
        assertEquals(byId.get("id"), add("https://us02web.zoom.us/w/" + SCHEDULED + "?tk=abc").get("id"), "the same webinar once");

        Map<String, Object> floating = add(UNSCHEDULED);
        assertNull(floating.get("scheduledStartAt"), "Zoom has no schedule: none is invented");
        assertEquals(true, floating.get("recurring"));

        assertEquals(400, api.post("/api/v1/zoom/monitors", HttpApi.json(Map.of("webinar", "https://zoom.us/webinar/register/WN_abc")), admin).statusCode());
        assertEquals(404, api.post("/api/v1/zoom/monitors", HttpApi.json(Map.of("webinar", "84499999999")), admin).statusCode(),
                "not in the academy's Zoom account");

        // Zoom changes the time and title; LUMA follows after a sync, without any edit in LUMA.
        SCHEDULED_TOPIC.set("A/L Physics 2027 (moved)");
        SCHEDULED_START.set(SCHEDULED_AT.plus(1, ChronoUnit.HOURS));
        Map<String, Object> synced = HttpApi.parse(api.post("/api/v1/zoom/monitors/" + byId.get("id") + "/sync", "{}", admin));
        assertEquals("A/L Physics 2027 (moved)", synced.get("topic"));
        assertEquals(SCHEDULED_AT.plus(1, ChronoUnit.HOURS).toString(), Instant.parse((String) synced.get("scheduledStartAt")).toString());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE academy_id = ? AND action = 'ZOOM_WEBINAR_MONITORED' "
                + "AND target_id = ?::uuid", Integer.class, academyId, byId.get("id")));
    }

    @Test
    void aRunStartedTwoHoursEarlyIsLiveAtOnceAndCountsJoinsAndRejoinsFromTheActualStart() {
        linkAcademyClass();
        Map<String, Object> monitor = add(SCHEDULED);
        assertEquals(PHYSICS, monitor.get("academyClassName"), "the Zoom title names exactly one Academy class");
        assertEquals("EXACT_TITLE", monitor.get("classSource"));
        assertEquals(batch, monitor.get("classBatchId"));

        Instant actual = SCHEDULED_AT.minus(2, ChronoUnit.HOURS); // 16:00 for an 18:00 webinar
        started(SCHEDULED, "Run-A==", actual);
        visit(SCHEDULED, "Run-A==", "amaya@example.test", "v1", actual.plus(5, ChronoUnit.MINUTES), actual.plus(40, ChronoUnit.MINUTES));
        visit(SCHEDULED, "Run-A==", "amaya@example.test", "v2", actual.plus(45, ChronoUnit.MINUTES), actual.plus(90, ChronoUnit.MINUTES));
        visit(SCHEDULED, "Run-A==", "amaya@example.test", "v3", actual.plus(50, ChronoUnit.MINUTES), actual.plus(60, ChronoUnit.MINUTES)); // overlap
        process();

        Map<String, Object> run = run("Run-A==");
        assertEquals("LIVE", run.get("state"));
        assertEquals(actual, ((Timestamp) run.get("actual_started_at")).toInstant());
        Object session = run.get("class_session_id");
        assertEquals(actual, jdbc.queryForObject("SELECT starts_at FROM class_sessions WHERE id = ?", Timestamp.class, session).toInstant(),
                "attendance is bounded by the actual start, not the 18:00 schedule");
        assertEquals("ZOOM_RUN", jdbc.queryForObject("SELECT origin FROM class_sessions WHERE id = ?", String.class, session));

        Map<String, Object> amaya = attendanceOf(session, "PCA001");
        assertEquals(true, amaya.get("expected"), "the webinar's Academy class is linked to the batch");
        assertEquals(4800, ((Number) amaya.get("connectedSeconds")).intValue(), "35 + 45 = 80 minutes, the overlap counted once");
        assertEquals(3, amaya.get("visits"));
        Map<String, Object> kavin = attendanceOf(session, "PCA002");
        assertEquals(true, kavin.get("expected"));
        assertEquals(0, ((Number) kavin.get("connectedSeconds")).intValue());

        Map<String, Object> classRun = HttpApi.parse(api.get("/api/v1/class-sessions/" + session + "/zoom-run", admin));
        assertEquals("LIVE", classRun.get("state"));
        assertEquals(actual, Instant.parse((String) classRun.get("actualStartedAt")), "actual start 16:00");
        assertEquals(SCHEDULED_AT, Instant.parse((String) classRun.get("scheduledStartAt")), "Zoom's 18:00 shown separately");
        assertEquals(403, api.get("/api/v1/class-sessions/" + session + "/zoom-run", tokenFor(academyId, Role.STUDENT)).statusCode());

        Map<String, Object> listed = HttpApi.parseList(api.get("/api/v1/zoom/monitors", admin)).stream().map(o -> (Map<String, Object>) o)
                .filter(m -> SCHEDULED.equals(m.get("webinarId"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> live = (Map<String, Object>) listed.get("live");
        assertEquals("Run-A==", live.get("occurrenceUuid"));

        ended(SCHEDULED, "Run-A==", actual, actual.plus(100, ChronoUnit.MINUTES));
        process();
        run = run("Run-A==");
        assertEquals("ENDED", run.get("state"));
        assertEquals(actual.plus(100, ChronoUnit.MINUTES), jdbc.queryForObject("SELECT ends_at FROM class_sessions WHERE id = ?",
                Timestamp.class, session).toInstant());
        assertEquals(4800, ((Number) attendanceOf(session, "PCA001").get("connectedSeconds")).intValue());
    }

    @Test
    void anUnscheduledWebinarAndALateStartAreTrackedTheSameWay() {
        add(UNSCHEDULED);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(10, ChronoUnit.MINUTES);
        started(UNSCHEDULED, "Float-1==", now);
        process();
        assertEquals("LIVE", run("Float-1==").get("state"));

        add(SCHEDULED);
        Instant late = SCHEDULED_AT.plus(45, ChronoUnit.MINUTES);
        started(SCHEDULED, "Late==", late);
        process();
        assertEquals(late, ((Timestamp) run("Late==").get("actual_started_at")).toInstant());
    }

    @Test
    void theSameWebinarIdOnThreeDaysMakesThreeSeparateRuns() {
        add(SCHEDULED);
        for (int day = 0; day < 3; day++) {
            Instant start = SCHEDULED_AT.minus(day, ChronoUnit.DAYS);
            String uuid = "Day-" + day + "==";
            started(SCHEDULED, uuid, start);
            visit(SCHEDULED, uuid, "amaya@example.test", "d" + day, start.plus(1, ChronoUnit.MINUTES), start.plus(11 + day, ChronoUnit.MINUTES));
            ended(SCHEDULED, uuid, start, start.plus(30, ChronoUnit.MINUTES));
        }
        process();
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM zoom_webinar_runs WHERE academy_id = ? AND webinar_id = ?",
                Integer.class, academyId, SCHEDULED));
        assertEquals(3, jdbc.queryForObject("SELECT count(DISTINCT class_session_id) FROM zoom_webinar_runs WHERE academy_id = ?",
                Integer.class, academyId));
        for (int day = 0; day < 3; day++) {
            Object session = run("Day-" + day + "==").get("class_session_id");
            assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT occurrence_uuid) FROM zoom_occurrence_links WHERE class_session_id = ?",
                    Integer.class, session), "no run mixes another day's evidence");
        }
    }

    @Test
    void duplicateLateAndOutOfOrderEventsGiveOneDeterministicRun() {
        add(SCHEDULED);
        Instant start = SCHEDULED_AT.minus(30, ChronoUnit.MINUTES);
        // Ended and a join arrive before started; started is delivered twice.
        ended(SCHEDULED, "Mixed==", start, start.plus(60, ChronoUnit.MINUTES));
        visit(SCHEDULED, "Mixed==", "amaya@example.test", "m1", start.plus(10, ChronoUnit.MINUTES), start.plus(20, ChronoUnit.MINUTES));
        process();
        Map<String, Object> early = run("Mixed==");
        assertEquals("ENDED", early.get("state"));
        assertEquals(start, ((Timestamp) early.get("actual_started_at")).toInstant(), "the ended event carries Zoom's start");
        started(SCHEDULED, "Mixed==", start);
        started(SCHEDULED, "Mixed==", start); // the same delivery again: stored once
        process();
        Map<String, Object> run = run("Mixed==");
        assertEquals("ENDED", run.get("state"), "a late start never reopens an ended run");
        assertEquals(start, ((Timestamp) run.get("actual_started_at")).toInstant());
        assertEquals(start.plus(60, ChronoUnit.MINUTES), ((Timestamp) run.get("actual_ended_at")).toInstant());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zoom_webinar_runs WHERE academy_id = ?", Integer.class, academyId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zoom_occurrence_events WHERE academy_id = ? AND occurrence_uuid = 'Mixed==' "
                + "AND event_type = 'webinar.started'", Integer.class, academyId));
    }

    @Test
    void aRunWhoseStartWasMissedStartsAtItsFirstEventAndUnmonitoredWebinarsAreLeftAlone() {
        add(SCHEDULED);
        Instant join = SCHEDULED_AT.minus(3, ChronoUnit.HOURS);
        visit(SCHEDULED, "NoStart==", "kavin@example.test", "n1", join, null);
        visit("84400009999", "Other==", "kavin@example.test", "o1", join, null);
        process();
        Map<String, Object> run = run("NoStart==");
        assertNull(run.get("actual_started_at"), "Zoom's start is unknown: not invented");
        assertEquals(join, ((Timestamp) run.get("first_seen_at")).toInstant());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_webinar_runs WHERE occurrence_uuid = 'Other=='", Integer.class));
    }

    @Test
    void stoppingMonitoringKeepsRunsAndOnlyStaffManageMonitors() {
        Map<String, Object> monitor = add(SCHEDULED);
        started(SCHEDULED, "Kept==", SCHEDULED_AT);
        process();
        Map<String, Object> stopped = HttpApi.parse(api.post("/api/v1/zoom/monitors/" + monitor.get("id") + "/stop", "{}", admin));
        assertEquals(false, stopped.get("active"));
        assertEquals(1, ((List<?>) stopped.get("runs")).size(), "history stays");
        started(SCHEDULED, "AfterStop==", SCHEDULED_AT.plus(1, ChronoUnit.DAYS));
        process();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_webinar_runs WHERE occurrence_uuid = 'AfterStop=='", Integer.class));

        String student = tokenFor(academyId, Role.STUDENT);
        for (String token : List.of(student)) {
            assertEquals(403, api.get("/api/v1/zoom/monitors", token).statusCode());
            assertEquals(403, api.post("/api/v1/zoom/monitors", HttpApi.json(Map.of("webinar", SCHEDULED)), token).statusCode());
        }
        UUID other = academy("Other academy");
        String otherAdmin = tokenFor(other, Role.STAFF);
        assertEquals(404, api.post("/api/v1/zoom/monitors/" + monitor.get("id") + "/sync", "{}", otherAdmin).statusCode());
        assertTrue(HttpApi.parseList(api.get("/api/v1/zoom/monitors", otherAdmin)).isEmpty());
    }

    @Test
    void anAmbiguousTitleStaysUnresolvedUntilStaffDecide() {
        // Two Academy classes normalise to the same name: not guessed.
        SCHEDULED_TOPIC.set("Physics Revision");
        Map<String, Object> monitor = add(SCHEDULED);
        assertNull(monitor.get("academyClassName"));
        assertEquals(false, monitor.get("classResolved"));
        started(SCHEDULED, "Ambiguous==", SCHEDULED_AT);
        visit(SCHEDULED, "Ambiguous==", "amaya@example.test", "a1", SCHEDULED_AT.plus(1, ChronoUnit.MINUTES), null);
        process();
        Object session = run("Ambiguous==").get("class_session_id");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM session_batches WHERE session_id = ?", Integer.class, session),
                "no expected students, so nobody can be marked absent");

        linkAcademyClass();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("academyClassName", PHYSICS);
        Map<String, Object> decided = HttpApi.parse(api.put("/api/v1/zoom/monitors/" + monitor.get("id") + "/academy-class",
                HttpApi.json(body), admin));
        assertEquals("ADMIN", decided.get("classSource"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM session_batches WHERE session_id = ?", Integer.class, session),
                "the run going on now gets the decided class's students");
        body.put("academyClassName", "Not an Academy class");
        assertEquals(404, api.put("/api/v1/zoom/monitors/" + monitor.get("id") + "/academy-class", HttpApi.json(body), admin).statusCode());
        assertNotEquals(null, decided.get("academyClassName"));
        assertFalse(((List<?>) decided.get("runs")).isEmpty());
    }

    @Test
    void aRunWhoseEndWasMissedIsClosedByZoomsReportAndAnEndedRunKeepsItsTimes() {
        add(SCHEDULED);
        started(SCHEDULED, "MissedEnd==", REPORT_START);
        visit(SCHEDULED, "MissedEnd==", "amaya@example.test", "r1", REPORT_START.plus(5, ChronoUnit.MINUTES), null);
        Instant endedStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(3, ChronoUnit.HOURS);
        started(SCHEDULED, "Ended==", endedStart);
        ended(SCHEDULED, "Ended==", endedStart, endedStart.plus(90, ChronoUnit.MINUTES));
        process();
        assertEquals("LIVE", run("MissedEnd==").get("state"));

        reports.runOnce();

        Map<String, Object> closed = run("MissedEnd==");
        assertEquals("ENDED", closed.get("state"), "closed from Zoom's report");
        assertEquals(REPORT_START, ((Timestamp) closed.get("actual_started_at")).toInstant());
        assertEquals(REPORT_START.plus(65, ChronoUnit.MINUTES), ((Timestamp) closed.get("actual_ended_at")).toInstant());
        assertEquals(REPORT_START.plus(65, ChronoUnit.MINUTES), jdbc.queryForObject("SELECT ends_at FROM class_sessions WHERE id = ?",
                Timestamp.class, closed.get("class_session_id")).toInstant());
        assertEquals("FETCHED", jdbc.queryForObject("SELECT status FROM zoom_report_fetches WHERE academy_id = ? AND occurrence_uuid = 'MissedEnd=='",
                String.class, academyId));

        Map<String, Object> ended = run("Ended==");
        assertEquals(endedStart.plus(90, ChronoUnit.MINUTES), ((Timestamp) ended.get("actual_ended_at")).toInstant(),
                "Zoom's own end event is kept when the report arrives");
        assertEquals("FETCHED", jdbc.queryForObject("SELECT status FROM zoom_report_fetches WHERE academy_id = ? AND occurrence_uuid = 'Ended=='",
                String.class, academyId));
    }

    @Test
    void anUnresolvedRunsClassIsOpenToEveryStaffMemberWithItsEvidence() {
        SCHEDULED_TOPIC.set("Physics Revision"); // ambiguous: no Academy class, so no batches
        add(SCHEDULED);
        Instant start = SCHEDULED_AT.minus(1, ChronoUnit.HOURS);
        started(SCHEDULED, "Open==", start);
        visit(SCHEDULED, "Open==", "amaya@example.test", "o1", start.plus(5, ChronoUnit.MINUTES), start.plus(35, ChronoUnit.MINUTES));
        fixtures.student("PCA001", "amaya@example.test");
        process();
        Object session = run("Open==").get("class_session_id");
        assertEquals(200, api.get("/api/v1/class-sessions/" + session, admin).statusCode(), "the Attendance link works");
        Map<String, Object> amaya = attendanceOf(session, "PCA001");
        assertEquals(false, amaya.get("expected"), "seen, not expected: nobody can be marked absent");
        assertEquals(1800, ((Number) amaya.get("connectedSeconds")).intValue());
        assertEquals(200, api.get("/api/v1/class-sessions/" + session + "/zoom-run", admin).statusCode());
        assertEquals(200, api.get("/api/v1/class-sessions/" + session, tokenFor(academyId, Role.STAFF)).statusCode(),
                "no batch assignments: every staff member opens it");
        assertEquals(403, api.get("/api/v1/class-sessions/" + session, tokenFor(academyId, Role.STUDENT)).statusCode());
    }

    @Test
    void addingAWebinarThatIsAlreadyRunningStartsItsRunAtZoomsActualStart() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(40, ChronoUnit.MINUTES);
        started(SCHEDULED, "Running==", start); // stored before anyone monitored the webinar
        visit(SCHEDULED, "Running==", "amaya@example.test", "b1", start.plus(2, ChronoUnit.MINUTES), null);
        process();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_webinar_runs WHERE academy_id = ?", Integer.class, academyId));

        Map<String, Object> monitor = add(SCHEDULED);
        Map<String, Object> run = run("Running==");
        assertEquals("LIVE", run.get("state"));
        assertEquals(start, ((Timestamp) run.get("actual_started_at")).toInstant(), "not the moment staff added it");
        assertNotEquals(null, monitor.get("live"));
    }

    @Test
    void stoppingMonitoringDuringARunStillEndsThatRun() {
        Map<String, Object> monitor = add(SCHEDULED);
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(50, ChronoUnit.MINUTES);
        started(SCHEDULED, "StopLive==", start);
        process();
        assertEquals(200, api.post("/api/v1/zoom/monitors/" + monitor.get("id") + "/stop", "{}", admin).statusCode());
        ended(SCHEDULED, "StopLive==", start, start.plus(45, ChronoUnit.MINUTES));
        process();
        assertEquals("ENDED", run("StopLive==").get("state"));
        assertEquals(start.plus(45, ChronoUnit.MINUTES), ((Timestamp) run("StopLive==").get("actual_ended_at")).toInstant());
    }
}
