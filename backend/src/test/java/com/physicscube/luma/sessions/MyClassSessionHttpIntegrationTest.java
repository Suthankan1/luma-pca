package com.physicscube.luma.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** GET /api/v1/me/class-sessions: a student's own classes, by enrolment on each class's date. */
class MyClassSessionHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final String WINDOW = "/api/v1/me/class-sessions?from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z";

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batchA;
    String batchB;
    String batchC;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        fixtures = new AcademyFixtures(api, admin);
        String physics = fixtures.course("PHY-2027");
        batchA = fixtures.batch(physics, "A");
        batchB = fixtures.batch(physics, "B");
        batchC = fixtures.batch(fixtures.course("CHEM-2027"), "C");
    }

    private String session(String title, String startsAt, String... batchIds) {
        String body = "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\""
                + java.time.Instant.parse(startsAt).plusSeconds(5400) + "\",\"timeZone\":\"Asia/Colombo\","
                + "\"zoomWebinarId\":\"81234567890\",\"batchIds\":[" + String.join(",", java.util.Arrays.stream(batchIds)
                .map(id -> '"' + id + '"').toList()) + "]}";
        HttpResponse<String> created = api.post("/api/v1/class-sessions", body, admin);
        assertEquals(201, created.statusCode(), created.body());
        return (String) HttpApi.parse(created).get("id");
    }

    /** Enrols, optionally withdraws, activates the account and returns the student's access token. */
    private String student(String number, Map<String, String[]> enrolments) {
        Map<String, Object> created = fixtures.student(number, number.toLowerCase() + "@example.invalid");
        for (Map.Entry<String, String[]> e : enrolments.entrySet()) {
            HttpResponse<String> enrolled = fixtures.enroll(e.getKey(), (String) created.get("id"), e.getValue()[0]);
            assertEquals(201, enrolled.statusCode(), enrolled.body());
            if (e.getValue().length > 1) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("enrolledOn", e.getValue()[0]);
                fields.put("withdrawnOn", e.getValue()[1]);
                fields.put("version", 0);
                assertEquals(200, api.put("/api/v1/enrollments/" + HttpApi.parse(enrolled).get("id"), HttpApi.json(fields), admin)
                        .statusCode());
            }
        }
        activate(UUID.fromString((String) created.get("accountId")));
        return login(number.toLowerCase() + "@example.invalid");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parseList(response).stream().map(item -> (Map<String, Object>) item).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> batchCodes(Map<String, Object> view) {
        return ((List<Map<String, Object>>) view.get("batches")).stream().map(b -> (String) b.get("code")).toList();
    }

    @Test
    void aStudentSeesTheClassesOfTheirBatchesOnTheDatesTheyAreEnrolled() {
        Map<String, String[]> enrolments = new LinkedHashMap<>();
        enrolments.put(batchA, new String[] {"2027-01-05", "2027-01-20"});
        enrolments.put(batchB, new String[] {"2027-01-10"});
        String token = student("PCA-001", enrolments);

        session("Before A", "2027-01-04T12:30:00Z", batchA);                  // before the enrolment in A
        String first = session("Waves", "2027-01-06T12:30:00Z", batchA);       // A only
        session("B early", "2027-01-08T12:30:00Z", batchB);                    // before the enrolment in B
        String shared = session("Shared", "2027-01-12T12:30:00Z", batchA, batchB);
        String afterWithdrawal = session("Late", "2027-01-25T12:30:00Z", batchA, batchB); // withdrawn from A by then
        session("Chemistry", "2027-01-13T12:30:00Z", batchC);                  // not their batch
        String cancelled = session("Cancelled", "2027-01-07T12:30:00Z", batchA);
        HttpResponse<String> current = api.get("/api/v1/class-sessions/" + cancelled, admin);
        Map<String, Object> c = HttpApi.parse(current);
        String cancelBody = "{\"title\":\"Cancelled\",\"startsAt\":\"" + c.get("startsAt") + "\",\"endsAt\":\"" + c.get("endsAt")
                + "\",\"timeZone\":\"Asia/Colombo\",\"batchIds\":[\"" + batchA + "\"],\"status\":\"CANCELLED\",\"version\":"
                + ((Number) c.get("version")).longValue() + "}";
        assertEquals(200, api.put("/api/v1/class-sessions/" + cancelled, cancelBody, admin).statusCode());

        List<Map<String, Object>> mine = list(api.get(WINDOW, token));

        assertEquals(List.of(first, cancelled, shared, afterWithdrawal), mine.stream().map(m -> m.get("id")).toList());
        assertEquals(List.of("A"), batchCodes(mine.get(0)));
        assertEquals("CANCELLED", mine.get(1).get("status"));
        assertEquals("https://zoom.us/j/81234567890", mine.get(0).get("joinUrl"), "the Academy asked for joining links");
        assertEquals(null, mine.get(1).get("joinUrl"), "no join link for a cancelled class");
        assertEquals(List.of("A", "B"), batchCodes(mine.get(2)));
        assertEquals(List.of("B"), batchCodes(mine.get(3)), "no longer listed under the batch they left");
        assertEquals("2027-01-06", mine.get(0).get("localDate"));
        assertEquals("Asia/Colombo", mine.get(0).get("timeZone"));
        for (Map<String, Object> view : mine) {
            assertFalse(view.containsKey("zoomWebinarId"), "students do not get the webinar ID");
            assertEquals(java.util.Set.of("id", "title", "startsAt", "endsAt", "timeZone", "localDate", "status", "batches", "joinUrl", "origin"),
                    view.keySet());
        }
    }

    @Test
    void studentsSeeOnlyTheirOwnAndOthersAreRefused() {
        session("Waves", "2027-01-06T12:30:00Z", batchA);
        String inA = student("PCA-001", Map.of(batchA, new String[] {"2027-01-01"}));
        String inC = student("PCA-002", Map.of(batchC, new String[] {"2027-01-01"}));
        String none = student("PCA-003", Map.of());

        assertEquals(1, list(api.get(WINDOW, inA)).size());
        assertEquals(List.of(), list(api.get(WINDOW, inC)));
        assertEquals(List.of(), list(api.get(WINDOW, none)));

        assertEquals(403, api.get(WINDOW, admin).statusCode(), "staff use /api/v1/class-sessions");
        assertEquals(403, api.get(WINDOW, tokenFor(academyId, Role.STAFF)).statusCode());
        assertEquals(401, api.get(WINDOW, null).statusCode());
        assertEquals(400, api.get("/api/v1/me/class-sessions?from=2027-01-01T00:00:00Z&to=2027-06-01T00:00:00Z", inA).statusCode(),
                "at most 92 days");
        assertTrue(api.get("/api/v1/me/class-sessions?from=2027-01-02T00:00:00Z&to=2027-01-01T00:00:00Z", inA).statusCode() == 400);
    }
}
