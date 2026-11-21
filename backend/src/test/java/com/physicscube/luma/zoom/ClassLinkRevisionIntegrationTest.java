package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
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

/** Automatic Zoom links follow the timetable when a class is created, moved, re-webinared or cancelled. */
class ClassLinkRevisionIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";
    private static final String OTHER_WEBINAR = "89999999999";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ZoomWebhookReceipts receipts;

    UUID academyId;
    String admin;
    String batch;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "A");
    }

    private String body(String startsAt, String endsAt, String webinarId, String status, Long version) {
        StringBuilder json = new StringBuilder("{\"title\":\"Lesson\",\"startsAt\":\"").append(startsAt).append("\",\"endsAt\":\"")
                .append(endsAt).append("\",\"batchIds\":[\"").append(batch).append("\"]");
        if (webinarId != null) {
            json.append(",\"zoomWebinarId\":\"").append(webinarId).append('"');
        }
        if (status != null) {
            json.append(",\"status\":\"").append(status).append("\",\"version\":").append(version);
        }
        return json.append('}').toString();
    }

    private String session(String startsAt, String endsAt, String webinarId) {
        HttpResponse<String> response = api.post("/api/v1/class-sessions", body(startsAt, endsAt, webinarId, null, null), admin);
        assertEquals(201, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("id");
    }

    private void edit(String sessionId, String startsAt, String endsAt, String webinarId, String status) {
        long version = ((Number) HttpApi.parse(api.get("/api/v1/class-sessions/" + sessionId, admin)).get("version")).longValue();
        HttpResponse<String> response = api.put("/api/v1/class-sessions/" + sessionId, body(startsAt, endsAt, webinarId, status, version), admin);
        assertEquals(200, response.statusCode(), response.body());
    }

    private void deliver(String webinarId, String occurrence, String at) {
        long eventTs = Instant.parse(at).toEpochMilli();
        String json = "{\"event\":\"webinar.started\",\"event_ts\":" + eventTs + ",\"payload\":{\"object\":{\"id\":\"" + webinarId
                + "\",\"uuid\":\"" + occurrence + "\"}}}";
        receipts.store(academyId, "webinar.started", eventTs, json.getBytes(StandardCharsets.UTF_8));
        processor.runOnce();
    }

    /** "session-id/SOURCE", or null when undecided. */
    private String link(String occurrence) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT class_session_id::text AS session, link_source FROM zoom_occurrence_links "
                + "WHERE occurrence_uuid = ?", occurrence);
        return rows.isEmpty() ? null : rows.get(0).get("session") + "/" + rows.get(0).get("link_source");
    }

    private List<Map<String, Object>> revisions() {
        return jdbc.queryForList("SELECT target_id::text AS session, details::text AS details FROM audit_events "
                + "WHERE action = 'ZOOM_LINKS_REVISED' ORDER BY occurred_at");
    }

    @Test
    void movingAClassAwayReturnsItsLinkToReviewAndMovingItBackRelinks() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);
        deliver(WEBINAR, "Occ==", "2027-03-01T08:31:00Z");
        assertEquals(lesson + "/AUTOMATIC", link("Occ=="));

        edit(lesson, "2027-03-08T08:30:00Z", "2027-03-08T10:30:00Z", WEBINAR, "SCHEDULED");
        assertNull(link("Occ=="), "the class moved a week: its Zoom occurrence goes back to review");

        edit(lesson, "2027-03-01T09:00:00Z", "2027-03-01T10:30:00Z", WEBINAR, "SCHEDULED");
        assertEquals(lesson + "/AUTOMATIC", link("Occ=="), "moved back: linked again");

        List<Map<String, Object>> audit = revisions();
        assertEquals(2, audit.size());
        assertEquals("{\"moved\": \"0\", \"linked\": \"0\", \"unlinked\": \"1\"}", audit.get(0).get("details"));
        assertEquals("{\"moved\": \"0\", \"linked\": \"1\", \"unlinked\": \"0\"}", audit.get(1).get("details"));
        assertEquals(lesson, audit.get(0).get("session"));

        // An edit that changes nothing about the Zoom match leaves the link and records nothing.
        edit(lesson, "2027-03-01T09:00:00Z", "2027-03-01T11:00:00Z", WEBINAR, "SCHEDULED");
        assertEquals(lesson + "/AUTOMATIC", link("Occ=="));
        assertEquals(2, revisions().size());
    }

    @Test
    void aNewWebinarIdOrCancellationRemovesTheLink() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);
        String other = session("2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", WEBINAR);
        deliver(WEBINAR, "Mon==", "2027-03-01T08:31:00Z");
        deliver(WEBINAR, "Tue==", "2027-03-02T08:31:00Z");

        edit(lesson, "2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", OTHER_WEBINAR, "SCHEDULED");
        assertNull(link("Mon=="), "the class is now on another webinar");
        edit(other, "2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", WEBINAR, "CANCELLED");
        assertNull(link("Tue=="), "a cancelled class keeps no automatic link");
        edit(other, "2027-03-02T08:30:00Z", "2027-03-02T10:30:00Z", WEBINAR, "SCHEDULED");
        assertEquals(other + "/AUTOMATIC", link("Tue=="), "restored: linked again");
    }

    @Test
    void aClashingNewClassSendsTheLinkToReviewAndCancellingOneLinksTheOther() {
        String first = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);
        deliver(WEBINAR, "Occ==", "2027-03-01T08:31:00Z");
        assertEquals(first + "/AUTOMATIC", link("Occ=="));

        String second = session("2027-03-01T09:00:00Z", "2027-03-01T10:00:00Z", WEBINAR);
        assertNull(link("Occ=="), "two classes now fit: nothing is guessed");

        edit(first, "2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR, "CANCELLED");
        assertEquals(second + "/AUTOMATIC", link("Occ=="), "only the second class fits now");
    }

    @Test
    void manualDecisionsAreNeverChangedByTimetableEdits() {
        String lesson = session("2027-03-01T08:30:00Z", "2027-03-01T10:30:00Z", WEBINAR);
        deliver(WEBINAR, "Occ==", "2027-03-01T08:31:00Z");
        deliver(WEBINAR, "Rehearsal==", "2027-03-01T08:45:00Z");
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("occurrenceUuid", "Occ==");
        decision.put("sessionId", lesson);
        decision.put("reason", "Confirmed by the instructor");
        assertEquals(200, api.post("/api/v1/zoom/occurrence-links", HttpApi.json(decision), admin).statusCode());
        decision.put("occurrenceUuid", "Rehearsal==");
        decision.put("sessionId", null);
        decision.put("reason", "Staff rehearsal");
        assertEquals(200, api.post("/api/v1/zoom/occurrence-links", HttpApi.json(decision), admin).statusCode());

        edit(lesson, "2027-03-08T08:30:00Z", "2027-03-08T10:30:00Z", OTHER_WEBINAR, "SCHEDULED");
        assertEquals(lesson + "/MANUAL", link("Occ=="));
        assertEquals("null/MANUAL", link("Rehearsal=="));
        assertEquals(0, revisions().size());
    }

    @Test
    void aSeriesPicksUpEarlierEventsAndCancellingTheRestReleasesThem() {
        deliver(WEBINAR, "Week1==", "2027-03-01T08:31:00Z");
        deliver(WEBINAR, "Week2==", "2027-03-08T08:31:00Z");
        deliver(WEBINAR, "Unrelated==", "2027-03-03T08:31:00Z");
        assertNull(link("Week1=="));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("title", "Theory");
        series.put("firstDate", "2027-03-01");
        series.put("lastDate", "2027-03-08");
        series.put("weekdays", List.of("MONDAY"));
        series.put("startTime", "14:00");
        series.put("endTime", "16:00");
        series.put("batchIds", List.of(batch));
        series.put("zoomWebinarId", WEBINAR);
        HttpResponse<String> created = api.post("/api/v1/class-sessions/series", toJson(series), admin);
        assertEquals(200, created.statusCode(), created.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) HttpApi.parse(created).get("occurrences");
        assertEquals(classes.get(0).get("id") + "/AUTOMATIC", link("Week1=="), "14:00 Colombo is 08:30 UTC");
        assertEquals(classes.get(1).get("id") + "/AUTOMATIC", link("Week2=="));
        assertNull(link("Unrelated=="), "a Wednesday event has no class");

        String seriesId = (String) HttpApi.parse(created).get("seriesId");
        assertEquals(200, api.post("/api/v1/class-sessions/series/" + seriesId + "/cancellations", "{\"fromDate\":\"2027-03-08\"}", admin)
                .statusCode());
        assertEquals(classes.get(0).get("id") + "/AUTOMATIC", link("Week1=="));
        assertNull(link("Week2=="), "the cancelled week's occurrence goes back to review");
    }

    private static String toJson(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
