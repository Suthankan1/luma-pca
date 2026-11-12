package com.physicscube.luma.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** POST /api/v1/class-sessions/series and the cancellation of the rest of a series. */
class ClassSeriesHttpIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batchA;
    String batchB;
    String batchC;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        fixtures = new AcademyFixtures(api, admin);
        String physics = fixtures.course("PHY-2027");
        batchA = fixtures.batch(physics, "A");
        batchB = fixtures.batch(physics, "B");
        batchC = fixtures.batch(fixtures.course("CHEM-2027"), "C");
    }

    /** Mondays and Wednesdays in January 2027, 18:00–20:00 Colombo, skipping 13 January. */
    private Map<String, Object> series(Object... overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Waves");
        body.put("firstDate", "2027-01-04");
        body.put("lastDate", "2027-01-31");
        body.put("weekdays", List.of("MONDAY", "wednesday"));
        body.put("startTime", "18:00");
        body.put("endTime", "20:00");
        body.put("timeZone", "Asia/Colombo");
        body.put("batchIds", List.of(batchA, batchB));
        body.put("zoomWebinarId", "812 3456 7890");
        body.put("skipDates", List.of("2027-01-13"));
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return body;
    }

    private HttpResponse<String> post(Map<String, Object> body, String token) {
        return api.post("/api/v1/class-sessions/series", json(body), token);
    }

    private Map<String, Object> ok(Map<String, Object> body) {
        HttpResponse<String> response = post(body, admin);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private static String json(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }

    private int classes() {
        return jdbc.queryForObject("SELECT count(*) FROM class_sessions WHERE academy_id = ?", Integer.class, academyId);
    }

    private String single(String title, String startsAt, String endsAt, String batchId) {
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt
                + "\",\"endsAt\":\"" + endsAt + "\",\"timeZone\":\"Asia/Colombo\",\"batchIds\":[\"" + batchId + "\"]}", admin);
        assertEquals(201, created.statusCode(), created.body());
        return (String) HttpApi.parse(created).get("id");
    }

    @Test
    void aPreviewListsTheClassesThenTheSeriesCreatesThemTogether() {
        Map<String, Object> preview = ok(series("dryRun", true));
        assertEquals(false, preview.get("created"));
        assertNull(preview.get("seriesId"));
        List<Map<String, Object>> planned = list(preview, "occurrences");
        assertEquals(List.of("2027-01-04", "2027-01-06", "2027-01-11", "2027-01-18", "2027-01-20", "2027-01-25", "2027-01-27"),
                planned.stream().map(o -> o.get("localDate")).toList(), "Mondays and Wednesdays, without the skipped 13th");
        assertEquals("2027-01-04T12:30:00Z", planned.get(0).get("startsAt"), "18:00 in Colombo is 12:30 UTC");
        assertEquals("2027-01-04T14:30:00Z", planned.get(0).get("endsAt"));
        assertNull(planned.get(0).get("id"));
        assertEquals(0, classes(), "a preview creates nothing");

        Map<String, Object> created = ok(series());
        assertEquals(true, created.get("created"));
        String seriesId = (String) created.get("seriesId");
        assertNotNull(seriesId);
        assertEquals(7, list(created, "occurrences").size());
        assertEquals(7, classes());

        List<Object> listed = HttpApi.parseList(api.get(
                "/api/v1/class-sessions?from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z", admin));
        assertEquals(7, listed.size());
        for (Object item : listed) {
            @SuppressWarnings("unchecked")
            Map<String, Object> session = (Map<String, Object>) item;
            assertEquals(seriesId, session.get("seriesId"));
            assertEquals("SCHEDULED", session.get("status"));
            assertEquals("81234567890", session.get("zoomWebinarId"));
            assertEquals(2, ((List<?>) session.get("batches")).size());
        }
        // Each class remains its own record: a single class can still be edited alone.
        String first = (String) list(created, "occurrences").get(0).get("id");
        Map<String, Object> one = HttpApi.parse(api.get("/api/v1/class-sessions/" + first, admin));
        assertEquals("Waves", one.get("title"));
    }

    @Test
    void overlapsWithScheduledClassesOfTheSameBatchesAreReportedAndBlockCreation() {
        String clash = single("Revision", "2027-01-11T12:00:00Z", "2027-01-11T13:00:00Z", batchB);
        String cancelled = single("Old", "2027-01-18T12:30:00Z", "2027-01-18T14:30:00Z", batchA);
        Map<String, Object> current = HttpApi.parse(api.get("/api/v1/class-sessions/" + cancelled, admin));
        assertEquals(200, api.put("/api/v1/class-sessions/" + cancelled, "{\"title\":\"Old\",\"startsAt\":\"2027-01-18T12:30:00Z\","
                + "\"endsAt\":\"2027-01-18T14:30:00Z\",\"timeZone\":\"Asia/Colombo\",\"batchIds\":[\"" + batchA + "\"],"
                + "\"status\":\"CANCELLED\",\"version\":" + ((Number) current.get("version")).longValue() + "}", admin).statusCode());
        single("Chemistry", "2027-01-25T12:30:00Z", "2027-01-25T14:30:00Z", batchC);
        single("Just after", "2027-01-27T14:30:00Z", "2027-01-27T15:30:00Z", batchA);

        Map<String, Object> refused = ok(series());
        assertEquals(false, refused.get("created"));
        List<Map<String, Object>> conflicts = list(refused, "conflicts");
        assertEquals(1, conflicts.size(), "cancelled classes, other batches and back-to-back classes do not clash");
        assertEquals(Map.of("localDate", "2027-01-11", "sessionId", clash, "title", "Revision",
                "startsAt", "2027-01-11T12:00:00Z", "endsAt", "2027-01-11T13:00:00Z"), conflicts.get(0));
        assertEquals(4, classes(), "nothing created while a class would overlap");

        Map<String, Object> created = ok(series("skipDates", List.of("2027-01-13", "2027-01-11")));
        assertEquals(true, created.get("created"));
        assertEquals(6, list(created, "occurrences").size());
    }

    @Test
    void theRestOfASeriesIsCancelledFromADate() {
        String seriesId = (String) ok(series()).get("seriesId");
        String path = "/api/v1/class-sessions/series/" + seriesId + "/cancellations";

        HttpResponse<String> cancelled = api.post(path, "{\"fromDate\":\"2027-01-20\"}", admin);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals(Map.of("cancelled", 3), HttpApi.parse(cancelled));
        assertEquals(List.of("SCHEDULED", "SCHEDULED", "SCHEDULED", "SCHEDULED", "CANCELLED", "CANCELLED", "CANCELLED"),
                jdbc.queryForList("SELECT status FROM class_sessions WHERE series_id = ?::uuid ORDER BY starts_at", String.class, seriesId));
        assertEquals(Map.of("cancelled", 0), HttpApi.parse(api.post(path, "{\"fromDate\":\"2027-01-20\"}", admin)), "already cancelled");

        assertEquals(404, api.post("/api/v1/class-sessions/series/" + UUID.randomUUID() + "/cancellations",
                "{\"fromDate\":\"2027-01-20\"}", admin).statusCode());
        UUID other = academy("Other Academy");
        assertEquals(404, api.post(path, "{\"fromDate\":\"2027-01-01\"}", tokenFor(other, Role.ACADEMY_ADMIN)).statusCode(),
                "another academy cannot touch the series");
        assertEquals(403, api.post(path, "{\"fromDate\":\"2027-01-01\"}", tokenFor(academyId, Role.INSTRUCTOR)).statusCode());
        assertEquals(400, api.post(path, "{}", admin).statusCode());
    }

    @Test
    void localTimesStayTheSameAcrossADaylightSavingChange() {
        Map<String, Object> london = ok(series("firstDate", "2027-03-22", "lastDate", "2027-03-31", "weekdays", List.of("MONDAY"),
                "timeZone", "Europe/London", "skipDates", List.of(), "dryRun", true));
        List<Map<String, Object>> planned = list(london, "occurrences");
        assertEquals("2027-03-22T18:00:00Z", planned.get(0).get("startsAt"), "18:00 GMT");
        assertEquals("2027-03-29T17:00:00Z", planned.get(1).get("startsAt"), "18:00 BST after the clocks change on 28 March");
    }

    @Test
    void invalidSeriesAreRefused() {
        assertEquals(400, post(series("weekdays", List.of()), admin).statusCode());
        assertEquals(400, post(series("weekdays", List.of("FUNDAY")), admin).statusCode());
        assertEquals(400, post(series("endTime", "17:00"), admin).statusCode(), "ends before it starts");
        assertEquals(400, post(series("lastDate", "2027-01-01"), admin).statusCode(), "last date before the first");
        assertEquals(400, post(series("lastDate", "2028-02-01"), admin).statusCode(), "more than a year");
        HttpResponse<String> tooMany = post(series("lastDate", "2027-06-30", "weekdays",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")), admin);
        assertEquals(400, tooMany.statusCode());
        assertTrue(String.valueOf(HttpApi.parse(tooMany).get("detail")).contains("between 1 and 100 classes"));
        assertEquals(400, post(series("firstDate", "2027-01-13", "lastDate", "2027-01-13", "weekdays", List.of("WEDNESDAY")), admin)
                .statusCode(), "only a skipped date: no classes");
        assertEquals(400, post(series("zoomWebinarId", "abc"), admin).statusCode());
        assertEquals(403, post(series(), tokenFor(academyId, Role.INSTRUCTOR)).statusCode());
        assertEquals(401, post(series(), null).statusCode());
        jdbc.update("UPDATE batches SET active = false WHERE id = ?::uuid", batchB);
        assertEquals(409, post(series(), admin).statusCode(), "inactive batch");
        assertEquals(0, classes());
    }

    private HttpResponse<String> edit(String seriesId, Map<String, Object> changes, String token) {
        return api.post("/api/v1/class-sessions/series/" + seriesId + "/changes", json(changes), token);
    }

    private static Map<String, Object> changes(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    private List<Map<String, Object>> rows(String seriesId) {
        return jdbc.queryForList("SELECT to_char(starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI') AS starts, title, status, "
                + "coalesce(zoom_webinar_id, '-') AS webinar FROM class_sessions WHERE series_id = ?::uuid ORDER BY starts_at", seriesId);
    }

    @Test
    void theRestOfASeriesIsRetimedAndRenamedFromADateAfterAPreview() {
        Map<String, Object> created = ok(series());
        String seriesId = (String) created.get("seriesId");
        String cancelled = (String) list(created, "occurrences").get(4).get("id");  // 20 January
        Map<String, Object> current = HttpApi.parse(api.get("/api/v1/class-sessions/" + cancelled, admin));
        assertEquals(200, api.put("/api/v1/class-sessions/" + cancelled, "{\"title\":\"Waves\",\"startsAt\":\"" + current.get("startsAt")
                + "\",\"endsAt\":\"" + current.get("endsAt") + "\",\"timeZone\":\"Asia/Colombo\",\"batchIds\":[\"" + batchA + "\",\"" + batchB
                + "\"],\"zoomWebinarId\":\"81234567890\",\"status\":\"CANCELLED\",\"version\":" + ((Number) current.get("version")).longValue() + "}",
                admin).statusCode());
        List<Map<String, Object>> before = rows(seriesId);

        HttpResponse<String> preview = edit(seriesId, changes("fromDate", "2027-01-18", "title", "Waves (new time)", "startTime", "17:30",
                "endTime", "19:30", "dryRun", true), admin);
        assertEquals(200, preview.statusCode(), preview.body());
        Map<String, Object> planned = HttpApi.parse(preview);
        assertEquals(false, planned.get("applied"));
        assertEquals(List.of("2027-01-18", "2027-01-25", "2027-01-27"), list(planned, "occurrences").stream().map(o -> o.get("localDate")).toList(),
                "scheduled classes from the date; the cancelled 20th is left alone");
        assertEquals("2027-01-18T12:00:00Z", list(planned, "occurrences").get(0).get("startsAt"), "17:30 Colombo");
        assertEquals(before, rows(seriesId), "a preview changes nothing");

        Map<String, Object> applied = HttpApi.parse(edit(seriesId, changes("fromDate", "2027-01-18", "title", "Waves (new time)",
                "startTime", "17:30", "endTime", "19:30"), admin));
        assertEquals(true, applied.get("applied"));
        List<Map<String, Object>> after = rows(seriesId);
        assertEquals(before.subList(0, 3), after.subList(0, 3), "the 4th, 6th and 11th, before the date, are untouched");
        assertEquals(Map.of("starts", "2027-01-18T12:00", "title", "Waves (new time)", "status", "SCHEDULED", "webinar", "81234567890"), after.get(3));
        assertEquals(Map.of("starts", "2027-01-20T12:30", "title", "Waves", "status", "CANCELLED", "webinar", "81234567890"), after.get(4));
        assertEquals(Map.of("starts", "2027-01-25T12:00", "title", "Waves (new time)", "status", "SCHEDULED", "webinar", "81234567890"), after.get(5));
        assertEquals("2027-01-27T12:00", after.get(6).get("starts"));

        Map<String, Object> audit = jdbc.queryForMap("SELECT target_id::text AS target, details::text AS details FROM audit_events "
                + "WHERE action = 'CLASS_SERIES_UPDATED'");
        assertEquals(seriesId, audit.get("target"));
        assertEquals("{\"changed\": [\"time\", \"title\"], \"classes\": \"3\", \"endTime\": \"19:30\", \"fromDate\": \"2027-01-18\", "
                + "\"startTime\": \"17:30\"}", audit.get("details"));
    }

    @Test
    void aRetimeThatWouldOverlapAnotherClassChangesNothing() {
        String seriesId = (String) ok(series()).get("seriesId");
        HttpResponse<String> clash = api.post("/api/v1/class-sessions", "{\"title\":\"Revision\",\"startsAt\":\"2027-01-25T11:30:00Z\","
                + "\"endsAt\":\"2027-01-25T12:15:00Z\",\"timeZone\":\"Asia/Colombo\",\"batchIds\":[\"" + batchB + "\"]}", admin);
        assertEquals(201, clash.statusCode(), clash.body());
        api.post("/api/v1/class-sessions", "{\"title\":\"Chemistry\",\"startsAt\":\"2027-01-27T12:00:00Z\",\"endsAt\":\"2027-01-27T13:00:00Z\","
                + "\"timeZone\":\"Asia/Colombo\",\"batchIds\":[\"" + batchC + "\"]}", admin);
        List<Map<String, Object>> before = rows(seriesId);

        Map<String, Object> refused = HttpApi.parse(edit(seriesId, changes("fromDate", "2027-01-18", "startTime", "17:30", "endTime", "19:30"), admin));

        assertEquals(false, refused.get("applied"));
        assertEquals(List.of("2027-01-25"), list(refused, "conflicts").stream().map(c -> c.get("localDate")).toList(),
                "only the class sharing a batch clashes");
        assertEquals("Revision", list(refused, "conflicts").get(0).get("title"));
        assertEquals(before, rows(seriesId));
        assertEquals(false, HttpApi.parse(edit(seriesId, changes("fromDate", "2027-01-18", "startTime", "17:30", "endTime", "19:30",
                "title", "Moved"), admin)).get("applied"), "still refused while the clash remains, title included");
        assertEquals(before, rows(seriesId));
    }

    @Test
    void theWebinarIsChangedOrClearedForTheRestOfASeries() {
        String seriesId = (String) ok(series()).get("seriesId");
        assertEquals(true, HttpApi.parse(edit(seriesId, changes("fromDate", "2027-01-20", "zoomWebinarId", "899 9999 9999"), admin)).get("applied"));
        assertEquals(List.of("81234567890", "81234567890", "81234567890", "81234567890", "89999999999", "89999999999", "89999999999"),
                rows(seriesId).stream().map(r -> r.get("webinar")).toList());
        assertEquals(true, HttpApi.parse(edit(seriesId, changes("fromDate", "2027-01-25", "clearWebinar", true), admin)).get("applied"));
        assertEquals(List.of("89999999999", "-", "-"), rows(seriesId).subList(4, 7).stream().map(r -> r.get("webinar")).toList());
    }

    @Test
    void invalidSeriesChangesAreRefused() {
        String seriesId = (String) ok(series()).get("seriesId");
        assertEquals(400, edit(seriesId, changes("fromDate", "2027-01-18"), admin).statusCode(), "nothing to change");
        assertEquals(400, edit(seriesId, changes("fromDate", "2027-01-18", "startTime", "17:30"), admin).statusCode());
        assertEquals(400, edit(seriesId, changes("fromDate", "2027-01-18", "startTime", "19:30", "endTime", "17:30"), admin).statusCode());
        assertEquals(400, edit(seriesId, changes("fromDate", "2027-01-18", "zoomWebinarId", "812 3456 7890", "clearWebinar", true), admin)
                .statusCode());
        assertEquals(400, edit(seriesId, changes("fromDate", "2027-02-01", "title", "Late"), admin).statusCode(), "no classes from then");
        assertEquals(400, edit(seriesId, changes("title", "No date"), admin).statusCode());
        assertEquals(404, edit(UUID.randomUUID().toString(), changes("fromDate", "2027-01-18", "title", "x"), admin).statusCode());
        assertEquals(403, edit(seriesId, changes("fromDate", "2027-01-18", "title", "x"), tokenFor(academyId, Role.INSTRUCTOR)).statusCode());
        assertEquals(404, edit(seriesId, changes("fromDate", "2027-01-18", "title", "x"), tokenFor(academy("Other"), Role.ACADEMY_ADMIN))
                .statusCode());
    }
}
