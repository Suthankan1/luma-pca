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
}
