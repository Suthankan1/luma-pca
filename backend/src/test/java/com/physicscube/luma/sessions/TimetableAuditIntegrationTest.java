package com.physicscube.luma.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every timetable change is in the activity log, with what changed; an edit that changes nothing is not. */
class TimetableAuditIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String admin;
    String batchA;
    String batchB;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String course = fixtures.course("PHY-2027");
        batchA = fixtures.batch(course, "A");
        batchB = fixtures.batch(course, "B");
    }

    private String body(String startsAt, String endsAt, String title, String status, long version, String... batches) {
        return "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\"" + endsAt + "\",\"batchIds\":["
                + String.join(",", java.util.Arrays.stream(batches).map(b -> '"' + b + '"').toList()) + "]"
                + (status == null ? "" : ",\"status\":\"" + status + "\",\"version\":" + version) + "}";
    }

    private long version(String path) {
        return ((Number) HttpApi.parse(api.get(path, admin)).get("version")).longValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> classActivity() {
        HttpResponse<String> response = api.get("/api/v1/audit-events?category=classes", admin);
        assertEquals(200, response.statusCode(), response.body());
        return (List<Map<String, Object>>) HttpApi.parse(response).get("items");
    }

    @Test
    void schedulingEditingCancellingAndRestoringAreRecorded() {
        HttpResponse<String> created = api.post("/api/v1/class-sessions",
                body("2027-03-01T12:30:00Z", "2027-03-01T14:30:00Z", "Waves", null, 0, batchA), admin);
        String id = (String) HttpApi.parse(created).get("id");
        String path = "/api/v1/class-sessions/" + id;

        assertEquals(200, api.put(path, body("2027-03-01T12:30:00Z", "2027-03-01T14:30:00Z", "Waves", "SCHEDULED", version(path), batchA), admin)
                .statusCode(), "saving with nothing changed");
        assertEquals(200, api.put(path, body("2027-03-02T12:30:00Z", "2027-03-02T14:30:00Z", "Waves II", "SCHEDULED", version(path), batchA, batchB),
                admin).statusCode());
        assertEquals(200, api.put(path, body("2027-03-02T12:30:00Z", "2027-03-02T14:30:00Z", "Waves II", "CANCELLED", version(path), batchA, batchB),
                admin).statusCode());
        assertEquals(200, api.put(path, body("2027-03-02T12:30:00Z", "2027-03-02T14:30:00Z", "Waves II", "SCHEDULED", version(path), batchA, batchB),
                admin).statusCode());

        List<Map<String, Object>> log = classActivity();
        assertEquals(List.of("CLASS_RESTORED", "CLASS_CANCELLED", "CLASS_UPDATED", "CLASS_SCHEDULED"),
                log.stream().map(e -> e.get("action")).toList(), "the no-change save is not recorded");
        log.forEach(e -> {
            assertEquals(id, e.get("targetId"));
            assertEquals("Waves II", e.get("targetLabel"), "labelled with the class's current title");
        });
        assertEquals(Map.of("changed", List.of("batches", "time", "title"), "startsAtBefore", "2027-03-01T12:30:00Z",
                "startsAt", "2027-03-02T12:30:00Z", "endsAt", "2027-03-02T14:30:00Z", "timeZone", "Asia/Colombo",
                "batchesAdded", "1", "batchesRemoved", "0"), log.get(2).get("details"));
        assertEquals(Map.of("changed", List.of()), log.get(1).get("details"));
        assertEquals(Map.of("startsAt", "2027-03-01T12:30:00Z", "endsAt", "2027-03-01T14:30:00Z", "timeZone", "Asia/Colombo",
                "batches", "1", "webinar", "none"), log.get(3).get("details"));
    }

    @Test
    void aSeriesAndCancellingTheRestAreRecordedOnceEach() {
        HttpResponse<String> created = api.post("/api/v1/class-sessions/series", "{\"title\":\"Theory\",\"firstDate\":\"2027-03-01\","
                + "\"lastDate\":\"2027-03-31\",\"weekdays\":[\"THURSDAY\",\"MONDAY\"],\"startTime\":\"18:00\",\"endTime\":\"20:00\","
                + "\"batchIds\":[\"" + batchA + "\"]}", admin);
        assertEquals(200, created.statusCode(), created.body());
        String seriesId = (String) HttpApi.parse(created).get("seriesId");
        assertEquals(200, api.post("/api/v1/class-sessions/series/" + seriesId + "/cancellations", "{\"fromDate\":\"2027-03-22\"}", admin)
                .statusCode());
        assertEquals(200, api.post("/api/v1/class-sessions/series/" + seriesId + "/cancellations", "{\"fromDate\":\"2027-03-22\"}", admin)
                .statusCode(), "nothing left to cancel: not recorded");

        List<Map<String, Object>> log = classActivity();
        assertEquals(List.of("CLASS_SERIES_CANCELLED", "CLASS_SERIES_CREATED"), log.stream().map(e -> e.get("action")).toList(),
                "one record per series, not one per class");
        assertEquals(Map.of("fromDate", "2027-03-22", "cancelled", "3"), log.get(0).get("details"));
        Map<?, ?> made = (Map<?, ?>) log.get(1).get("details");
        assertEquals("9", made.get("classes"));
        assertEquals(List.of("MONDAY", "THURSDAY"), made.get("weekdays"));
        assertEquals("2027-03-01", made.get("firstDate"));
        assertEquals("2027-03-29", made.get("lastDate"));
        assertTrue(log.stream().allMatch(e -> seriesId.equals(e.get("targetId")) && "CLASS_SERIES".equals(e.get("targetType"))));
    }
}
