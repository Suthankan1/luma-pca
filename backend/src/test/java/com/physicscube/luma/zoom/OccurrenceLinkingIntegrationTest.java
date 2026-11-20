package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class OccurrenceLinkingIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ZoomWebhookReceipts receipts;

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batch;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "A");
    }

    private static String sessionBody(String startsAt, String endsAt, String webinarId, String status, Integer version,
                                      String batchId) {
        StringBuilder json = new StringBuilder("{\"title\":\"Lesson\",\"startsAt\":\"").append(startsAt).append("\",\"endsAt\":\"")
                .append(endsAt).append("\",\"batchIds\":[\"").append(batchId).append("\"]");
        if (webinarId != null) {
            json.append(",\"zoomWebinarId\":\"").append(webinarId).append('"');
        }
        if (status != null) {
            json.append(",\"status\":\"").append(status).append("\",\"version\":").append(version);
        }
        return json.append('}').toString();
    }

    private String session(String startsAt, String endsAt, String webinarId) {
        HttpResponse<String> response = api.post("/api/v1/class-sessions", sessionBody(startsAt, endsAt, webinarId, null, null, batch), admin);
        assertEquals(201, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("id");
    }

    /** Stores a webhook receipt for this academy as the ingress would, then lets the worker process everything due. */
    private void deliver(UUID academy, String type, String webinarId, String occurrence, String at, int sequence) {
        long eventTs = Instant.parse(at).toEpochMilli() + sequence;
        String body = "{\"event\":\"" + type + "\",\"event_ts\":" + eventTs + ",\"payload\":{\"object\":{\"id\":\"" + webinarId
                + "\",\"uuid\":\"" + occurrence + "\"}}}";
        receipts.store(academy, type, eventTs, body.getBytes(StandardCharsets.UTF_8));
    }

    private void deliver(String type, String webinarId, String occurrence, String at) {
        deliver(academyId, type, webinarId, occurrence, at, 0);
        processor.runOnce();
    }

    private Map<String, Object> link(String occurrence) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT class_session_id::text AS session, link_source, linked_by "
                + "FROM zoom_occurrence_links WHERE occurrence_uuid = ?", occurrence);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> occurrences(String query) {
        HttpResponse<String> response = api.get("/api/v1/zoom/occurrences" + query, admin);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(item -> (Map<String, Object>) item).toList();
    }

    private HttpResponse<String> decide(String token, String occurrence, String sessionId, String reason) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("occurrenceUuid", occurrence);
        fields.put("sessionId", sessionId);
        fields.put("reason", reason);
        return api.post("/api/v1/zoom/occurrence-links", HttpApi.json(fields), token);
    }

    @Test
    void aClassTakesTheWebinarIdAsZoomDisplaysIt() {
        HttpResponse<String> created = api.post("/api/v1/class-sessions",
                sessionBody("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", "812 3456 7890", null, null, batch), admin);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(WEBINAR, HttpApi.parse(created).get("zoomWebinarId"));
        String path = "/api/v1/class-sessions/" + HttpApi.parse(created).get("id");

        for (String bad : List.of("abc4567890", "12345", "812-3456-7890-1234-5678-90")) {
            assertEquals(400, api.post("/api/v1/class-sessions",
                    sessionBody("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", bad, null, null, batch), admin).statusCode(), bad);
        }
        HttpResponse<String> cleared = api.put(path, sessionBody("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", null, "SCHEDULED", 0, batch), admin);
        assertNull(HttpApi.parse(cleared).get("zoomWebinarId"), "omitting it on a full update clears it");
        assertEquals("98765432101", HttpApi.parse(api.put(path, sessionBody("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z",
                "987-654-321-01", "SCHEDULED", 1, batch), admin)).get("zoomWebinarId"));
    }

    @Test
    void anOccurrenceIsLinkedToTheOneScheduledClassItFallsInto() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);

        deliver("webinar.started", WEBINAR, "Occ1/a+b==", "2027-03-01T08:25:00Z");
        deliver(academyId, "webinar.participant_joined", WEBINAR, "Occ1/a+b==", "2027-03-01T08:31:00Z", 1);
        deliver(academyId, "webinar.participant_left", WEBINAR, "Occ1/a+b==", "2027-03-01T10:29:00Z", 2);
        processor.runOnce();

        assertEquals(Map.of("session", lesson, "link_source", "AUTOMATIC"), Map.of("session", link("Occ1/a+b==").get("session"),
                "link_source", link("Occ1/a+b==").get("link_source")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zoom_occurrence_links", Integer.class));
        List<Map<String, Object>> listed = occurrences("?sessionId=" + lesson);
        assertEquals(1, listed.size());
        assertEquals("Occ1/a+b==", listed.get(0).get("occurrenceUuid"));
        assertEquals(3, ((Number) listed.get(0).get("eventCount")).intValue());
        assertEquals("2027-03-01T08:25:00Z", listed.get(0).get("firstEventAt"));
    }

    @Test
    void aRecurringWebinarLinksEachDaysOccurrenceToThatDaysClass() {
        String monday = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);
        String tuesday = session("2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", WEBINAR);

        deliver("webinar.started", WEBINAR, "MondayOcc==", "2027-03-01T08:28:00Z");
        deliver("webinar.started", WEBINAR, "TuesdayOcc==", "2027-03-02T08:28:00Z");
        // Restarted on Tuesday: Zoom gives the new instance a new occurrence UUID.
        deliver("webinar.started", WEBINAR, "TuesdayRestart==", "2027-03-02T09:05:00Z");

        assertEquals(monday, link("MondayOcc==").get("session"));
        assertEquals(tuesday, link("TuesdayOcc==").get("session"));
        assertEquals(tuesday, link("TuesdayRestart==").get("session"));
    }

    @Test
    void nothingIsGuessedWhenThereIsNoMatchOrMoreThanOne() {
        session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);
        session("2027-03-03T08:30:00Z", "2027-03-03T09:00:00Z", WEBINAR);
        session("2027-03-03T09:30:00Z", "2027-03-03T10:00:00Z", WEBINAR);
        String cancelled = session("2027-03-04T08:30:00Z", "2027-03-04T10:30:00Z", WEBINAR);
        assertEquals(200, api.put("/api/v1/class-sessions/" + cancelled,
                sessionBody("2027-03-04T08:30:00Z", "2027-03-04T10:30:00Z", WEBINAR, "CANCELLED", 0, batch), admin).statusCode());
        UUID otherAcademy = academy("Other Academy");
        AcademyFixtures theirs = new AcademyFixtures(api, tokenFor(otherAcademy, Role.STAFF));
        String theirBatch = theirs.batch(theirs.course("THEIRS"), "X");
        assertEquals(201, api.post("/api/v1/class-sessions", sessionBody("2027-03-05T08:30:00Z", "2027-03-05T10:30:00Z", WEBINAR,
                null, null, theirBatch), tokenFor(otherAcademy, Role.STAFF)).statusCode());

        deliver("webinar.started", WEBINAR, "TooLate==", "2027-03-01T11:31:00Z");
        deliver("webinar.started", "99999999999", "OtherWebinar==", "2027-03-01T08:31:00Z");
        deliver("webinar.started", WEBINAR, "Between==", "2027-03-03T09:15:00Z");
        deliver("webinar.started", WEBINAR, "Cancelled==", "2027-03-04T08:31:00Z");
        deliver("webinar.started", WEBINAR, "OtherAcademysClass==", "2027-03-05T08:31:00Z");
        deliver("webinar.started", WEBINAR, "EarlyEnough==", "2027-03-01T07:30:00Z");

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_occurrence_links WHERE occurrence_uuid <> 'EarlyEnough=='", Integer.class));
        assertEquals("AUTOMATIC", link("EarlyEnough==").get("link_source"), "exactly an hour early still matches");
        assertEquals(List.of("OtherAcademysClass==", "Cancelled==", "Between==", "TooLate==", "OtherWebinar=="),
                occurrences("?decided=false").stream().map(o -> o.get("occurrenceUuid")).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void administratorsDecideWithAnAuditedReasonAndTheMatcherNeverOverrulesThem() {
        String first = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", null);
        String second = session("2027-03-01T13:30:00Z", "2027-03-01T15:30:00Z", null);
        deliver("webinar.started", WEBINAR, "Occ1==", "2027-03-01T08:31:00Z");
        assertNull(link("Occ1=="));

        HttpResponse<String> linked = decide(admin, "Occ1==", first, "Instructor confirmed this was the 08:30 class");
        assertEquals(200, linked.statusCode(), linked.body());
        assertEquals("MANUAL", HttpApi.parse(linked).get("linkSource"));
        assertEquals(first, HttpApi.parse(linked).get("classSessionId"));

        assertEquals(200, decide(admin, "Occ1==", second, "Wrong class picked earlier").statusCode());
        assertEquals(second, link("Occ1==").get("session"));

        assertEquals(200, decide(admin, "Occ1==", null, "This was a staff rehearsal").statusCode());
        assertNull(link("Occ1==").get("session"));
        assertEquals("MANUAL", link("Occ1==").get("link_source"));
        // A class on that webinar now exists and a later event arrives: the manual decision stands.
        api.put("/api/v1/class-sessions/" + first, sessionBody("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR, "SCHEDULED", 0, batch), admin);
        deliver("webinar.ended", WEBINAR, "Occ1==", "2027-03-01T10:20:00Z");
        assertNull(link("Occ1==").get("session"));

        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, target_id::text AS target, details::text AS details "
                + "FROM audit_events WHERE action LIKE 'ZOOM\\_%' ORDER BY occurred_at");
        assertEquals(List.of("ZOOM_OCCURRENCE_LINKED", "ZOOM_OCCURRENCE_LINKED", "ZOOM_OCCURRENCE_MARKED_NOT_CLASS"),
                audit.stream().map(a -> a.get("action")).toList());
        assertEquals(first, audit.get(1).get("details").toString().replaceAll(".*\"previousSessionId\": \"([^\"]+)\".*", "$1"));
        assertEquals("{\"reason\": \"This was a staff rehearsal\", \"occurrenceUuid\": \"Occ1==\", \"previousSource\": \"MANUAL\", "
                + "\"previousSessionId\": \"" + second + "\"}", audit.get(2).get("details"));
    }

    @Test
    void manualDecisionsAreValidated() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", null);
        deliver("webinar.started", WEBINAR, "Occ1==", "2027-03-01T08:31:00Z");
        String cancelled = session("2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", null);
        api.put("/api/v1/class-sessions/" + cancelled, sessionBody("2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", null, "CANCELLED", 0, batch), admin);
        String theirAdmin = tokenFor(academy("Other Academy"), Role.STAFF);
        AcademyFixtures theirs = new AcademyFixtures(api, theirAdmin);
        HttpResponse<String> created = api.post("/api/v1/class-sessions", sessionBody("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z",
                null, null, null, theirs.batch(theirs.course("T"), "X")), theirAdmin);
        assertEquals(201, created.statusCode(), created.body());
        String theirSession = (String) HttpApi.parse(created).get("id");

        assertEquals(409, decide(admin, "Occ1==", cancelled, "valid reason").statusCode());
        assertEquals(404, decide(admin, "Occ1==", theirSession, "valid reason").statusCode());
        assertEquals(404, decide(admin, "Occ1==", UUID.randomUUID().toString(), "valid reason").statusCode());
        assertEquals(404, decide(admin, "NeverSeen==", lesson, "valid reason").statusCode());
        assertEquals(400, decide(admin, "Occ1==", lesson, "x").statusCode());
        assertEquals(400, decide(admin, "", lesson, "valid reason").statusCode());
        assertNull(link("Occ1=="));
    }

    @Test
    void occurrencesThatArrivedBeforeTheClassHadAWebinarIdAreLinkedWhenItGetsOne() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", null);
        deliver("webinar.started", WEBINAR, "Early==", "2027-03-01T08:31:00Z");
        deliver("webinar.started", WEBINAR, "Rehearsal==", "2027-03-01T08:40:00Z");
        assertEquals(200, decide(admin, "Rehearsal==", null, "Staff rehearsal").statusCode());
        assertNull(link("Early=="));
        // Giving the class its webinar ID re-checks the occurrences near it (slice ZM-5).
        assertEquals(200, api.put("/api/v1/class-sessions/" + lesson,
                sessionBody("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR, "SCHEDULED", 0, batch), admin).statusCode());
        assertEquals(lesson, link("Early==").get("session"));

        // Running the matcher by hand finds nothing left to link.
        HttpResponse<String> result = api.post("/api/v1/zoom/occurrences/auto-link", "", admin);

        assertEquals(200, result.statusCode(), result.body());
        assertEquals(Map.of("linked", 0, "stillUndecided", 0), HttpApi.parse(result).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> ((Number) e.getValue()).intValue())));
        assertEquals(lesson, link("Early==").get("session"));
        assertNull(link("Rehearsal==").get("session"), "manual decisions are left alone");
        assertEquals(List.of(), occurrences("?decided=false"));
    }

    @Test
    void concurrentWorkersCreateOneLinkPerOccurrence() throws Exception {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);
        for (int i = 0; i < 40; i++) {
            deliver(academyId, "webinar.participant_joined", WEBINAR, "Busy==", "2027-03-01T08:31:00Z", i);
        }
        int workers = 4;
        CyclicBarrier start = new CyclicBarrier(workers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Integer> handled = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, workers).<Callable<Integer>>mapToObj(w -> () -> {
                start.await();
                int count = 0;
                for (int n; (n = processor.runOnce()) > 0; ) {
                    count += n;
                }
                return count;
            }).toList())) {
                handled.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(40, handled.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zoom_occurrence_links", Integer.class));
        assertEquals(lesson, link("Busy==").get("session"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_webhook_receipts WHERE processing_status <> 'PROCESSED'", Integer.class));
    }

    @Test
    void occurrenceToolsAreForStaffOfTheSameAcademyOnly() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", null);
        deliver("webinar.started", WEBINAR, "Occ1==", "2027-03-01T08:31:00Z");

        for (Role role : List.of(Role.STUDENT)) {
            String token = tokenFor(academyId, role);
            assertEquals(403, api.get("/api/v1/zoom/occurrences", token).statusCode(), role.name());
            assertEquals(403, decide(token, "Occ1==", lesson, "valid reason").statusCode(), role.name());
            assertEquals(403, api.post("/api/v1/zoom/occurrences/auto-link", "", token).statusCode(), role.name());
        }
        String outsider = tokenFor(academy("Other Academy"), Role.STAFF);
        assertEquals("[]", api.get("/api/v1/zoom/occurrences", outsider).body());
        assertEquals(404, decide(outsider, "Occ1==", null, "valid reason").statusCode());
        assertEquals(401, api.get("/api/v1/zoom/occurrences", null).statusCode());
        assertEquals(400, api.get("/api/v1/zoom/occurrences?limit=500", admin).statusCode());
        assertNull(link("Occ1=="));
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", null);
        UUID other = academy("Other Academy");
        String insert = "INSERT INTO zoom_occurrence_links (academy_id, occurrence_uuid, webinar_id, class_session_id, link_source, "
                + "linked_by, linked_at) VALUES (?, ?, 'W', ?::uuid, ?, ?, NOW())";
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, academyId, "A", null, "AUTOMATIC", null),
                "an automatic link always names a class");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, academyId, "B", lesson, "MANUAL", null),
                "a manual decision names who made it");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, other, "C", lesson, "AUTOMATIC", null),
                "a class of another academy");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, academyId, "D", lesson, "GUESSED", null));
        assertEquals(1, jdbc.update(insert, academyId, "E", lesson, "AUTOMATIC", null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, academyId, "E", lesson, "AUTOMATIC", null),
                "one decision per occurrence");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE class_sessions SET zoom_webinar_id = '812 3456 7890' WHERE id = ?::uuid", lesson));
    }
}
