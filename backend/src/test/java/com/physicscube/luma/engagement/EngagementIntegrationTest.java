package com.physicscube.luma.engagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Engagement with consent (006A–C): off by default, opt-in, rate limits, pulses, staff-only rankings. */
class EngagementIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String owner;
    String admin;
    String batch;
    String live;
    Map<String, String> studentTokens = new LinkedHashMap<>();
    Map<String, String> studentIds = new LinkedHashMap<>();

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        owner = tokenFor(academyId, Role.STAFF);
        admin = tokenFor(academyId, Role.STAFF);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        for (String n : List.of("1", "2", "3")) {
            Map<String, Object> s = fixtures.student("E-00" + n, "eng" + n + "@example.test");
            studentIds.put(n, (String) s.get("id"));
            assertEquals(201, fixtures.enroll(batch, (String) s.get("id"), "2020-01-01").statusCode());
            activate(UUID.fromString((String) s.get("accountId")));
            studentTokens.put(n, login("eng" + n + "@example.test"));
        }
        // A class running now (started 20 minutes ago, two hours long).
        Instant start = Instant.now().minus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Live optics\",\"startsAt\":\"" + start + "\",\"endsAt\":\""
                + start.plus(2, ChronoUnit.HOURS) + "\",\"batchIds\":[\"" + batch + "\"]}", admin);
        assertEquals(201, created.statusCode(), created.body());
        live = (String) HttpApi.parse(created).get("id");
    }

    private HttpResponse<String> configure(String token, boolean enabled, String text, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("consentText", text);
        body.put("version", version);
        return api.put("/api/v1/engagement-settings", HttpApi.json(body), token);
    }

    private HttpResponse<String> consent(String n, boolean granted, boolean leaderboard, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("granted", granted);
        body.put("leaderboardOptIn", leaderboard);
        body.put("consentVersion", version);
        return api.put("/api/v1/me/engagement-consent", HttpApi.json(body), studentTokens.get(n));
    }

    private HttpResponse<String> react(String n, String kind) {
        return api.post("/api/v1/me/class-sessions/" + live + "/reactions", "{\"kind\":\"" + kind + "\"}", studentTokens.get(n));
    }

    private static final String WORDING = "LUMA records the reactions and answers you send from this portal during classes.";

    @Test
    void nothingIsRecordedUntilStaffSwitchItOnAndTheStudentConsents() {
        assertEquals(409, react("1", "UNDERSTOOD").statusCode(), "off by default");
        assertEquals(204, api.get("/api/v1/engagement-settings", admin).statusCode());
        assertEquals(403, configure(tokenFor(academyId, Role.STUDENT), true, WORDING, null).statusCode(), "staff only");
        assertEquals(400, configure(owner, true, "too short", null).statusCode());
        assertEquals(200, configure(owner, true, WORDING, null).statusCode());

        Map<String, Object> mine = HttpApi.parse(api.get("/api/v1/me/engagement-consent", studentTokens.get("1")));
        assertEquals(true, mine.get("enabled"));
        assertNull(mine.get("granted"), "not asked yet");
        assertEquals(409, react("1", "UNDERSTOOD").statusCode(), "no consent yet");
        assertEquals(400, consent("1", false, true, 1).statusCode(), "rankings need consent first");
        assertEquals(200, consent("1", true, false, 1).statusCode());
        assertEquals(204, react("1", "UNDERSTOOD").statusCode());
        assertEquals(409, react("1", "CONFUSED").statusCode(), "rate limit: one reaction per 10 seconds");
        assertEquals(400, react("1", "LOL").statusCode(), "unknown kind");
        assertEquals(409, react("2", "UNDERSTOOD").statusCode(), "student 2 has not consented");

        // New wording asks everyone again.
        long version = ((Number) HttpApi.parse(api.get("/api/v1/engagement-settings", owner)).get("version")).longValue();
        assertEquals(200, configure(owner, true, WORDING + " Reviewed in 2027.", version).statusCode());
        Map<String, Object> again = HttpApi.parse(api.get("/api/v1/me/engagement-consent", studentTokens.get("1")));
        assertEquals(2, again.get("consentVersion"));
        assertNull(again.get("granted"));
        assertEquals(409, consent("1", true, false, 1).statusCode(), "the old wording is no longer valid");

        Map<String, Object> staffView = HttpApi.parse(api.get("/api/v1/class-sessions/" + live + "/engagement", admin));
        assertEquals(1, ((Map<?, ?>) staffView.get("reactions")).get("UNDERSTOOD"));
        assertEquals(1, staffView.get("reactingStudents"));
        assertEquals(List.of("ENGAGEMENT_SETTINGS_CHANGED", "ENGAGEMENT_SETTINGS_CHANGED"), jdbc.queryForList("SELECT action FROM audit_events "
                + "WHERE academy_id = ? AND action LIKE 'ENGAGEMENT_SETTINGS%' ORDER BY occurred_at", String.class, academyId));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pulseQuestionsAreOpenedByStaffAndAnsweredOnce() {
        assertEquals(200, configure(owner, true, WORDING, null).statusCode());
        assertEquals(200, consent("1", true, true, 1).statusCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", "Which unit is force measured in?");
        body.put("options", List.of("Newton", "Joule", "Watt"));
        body.put("minutes", 5);
        assertEquals(403, api.post("/api/v1/class-sessions/" + live + "/pulses", toJson(body), studentTokens.get("1")).statusCode());
        body.put("minutes", 11);
        assertEquals(400, api.post("/api/v1/class-sessions/" + live + "/pulses", toJson(body), admin).statusCode(), "at most 10 minutes");
        body.put("minutes", 5);
        body.put("options", List.of("Newton", "Newton"));
        assertEquals(400, api.post("/api/v1/class-sessions/" + live + "/pulses", toJson(body), admin).statusCode(), "distinct options");
        body.put("options", List.of("Newton", "Joule", "Watt"));
        HttpResponse<String> opened = api.post("/api/v1/class-sessions/" + live + "/pulses", toJson(body), admin);
        assertEquals(201, opened.statusCode(), opened.body());
        String question = (String) HttpApi.parse(opened).get("id");

        Map<String, Object> seen = HttpApi.parse(api.get("/api/v1/me/class-sessions/" + live + "/engagement", studentTokens.get("1")));
        assertTrue(seen.get("classEndsAt") != null, "the student's page knows when the class ends");
        assertEquals(1, ((List<?>) seen.get("pulses")).size());
        String answer = "/api/v1/me/class-sessions/" + live + "/pulses/" + question + "/answers";
        assertEquals(400, api.post(answer, "{\"option\":3}", studentTokens.get("1")).statusCode());
        assertEquals(204, api.post(answer, "{\"option\":0}", studentTokens.get("1")).statusCode());
        assertEquals(409, api.post(answer, "{\"option\":1}", studentTokens.get("1")).statusCode(), "once");
        assertEquals(409, api.post(answer, "{\"option\":1}", studentTokens.get("2")).statusCode(), "no consent");

        Map<String, Object> staff = HttpApi.parse(api.get("/api/v1/class-sessions/" + live + "/engagement", admin));
        Map<String, Object> pulse = ((List<Map<String, Object>>) staff.get("pulses")).get(0);
        assertEquals(List.of(1, 0, 0), pulse.get("counts"));
        assertEquals(204, api.post("/api/v1/class-sessions/" + live + "/pulses/" + question + "/closure", "{}", admin).statusCode());
        assertEquals(409, api.post(answer, "{\"option\":0}", studentTokens.get("3")).statusCode(), "closed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rankingsAreStaffOnlyAndNameOnlyStudentsWhoOptedIn() {
        assertEquals(200, configure(owner, true, WORDING, null).statusCode());
        assertEquals(200, consent("1", true, true, 1).statusCode());
        assertEquals(200, consent("2", true, false, 1).statusCode());
        // Student 3 does not consent: never counted.
        jdbc.update("INSERT INTO portal_reactions (id, academy_id, class_session_id, student_id, kind, created_at) "
                + "SELECT gen_random_uuid(), ?, ?::uuid, ?::uuid, 'UNDERSTOOD', now() - (g || ' seconds')::interval FROM generate_series(1, 25) g",
                academyId, live, studentIds.get("1"));
        jdbc.update("INSERT INTO portal_reactions (id, academy_id, class_session_id, student_id, kind, created_at) "
                + "SELECT gen_random_uuid(), ?, ?::uuid, ?::uuid, 'CONFUSED', now() - (g || ' seconds')::interval FROM generate_series(1, 4) g",
                academyId, live, studentIds.get("2"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        String path = "/api/v1/batches/" + batch + "/engagement-ranking?from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
        Map<String, Object> ranking = HttpApi.parse(api.get(path + "&limit=10", admin));
        assertEquals(2, ranking.get("studentsCounted"), "consenting students only");
        List<Map<String, Object>> top = (List<Map<String, Object>>) ranking.get("top");
        assertEquals("E-001", top.get(0).get("studentNumber"));
        assertEquals(10, top.get(0).get("reactions"), "capped at 10 per class");
        assertNull(top.get(1).get("name"), "not opted in to rankings: counted but not named");
        assertNull(top.get(1).get("studentNumber"));
        assertTrue(((String) ranking.get("method")).contains("PORTAL_REACTION"));
        assertEquals(400, api.get(path + "&limit=7", admin).statusCode());

        // The same over the whole course, and as a CSV download (audited).
        String course = jdbc.queryForObject("SELECT course_id::text FROM batches WHERE id = ?::uuid", String.class, batch);
        String range = "?from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
        Map<String, Object> courseRanking = HttpApi.parse(api.get("/api/v1/courses/" + course + "/engagement-ranking" + range, admin));
        assertEquals(2, courseRanking.get("studentsCounted"));
        HttpResponse<String> csv = api.get("/api/v1/courses/" + course + "/engagement-ranking/export" + range + "&limit=100", admin);
        assertEquals(200, csv.statusCode(), csv.body());
        assertTrue(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        String[] lines = csv.body().split("\r\n");
        assertEquals("rank,student_number,student_name,classes,reactions,pulse_answers,score", lines[0]);
        assertTrue(lines[1].startsWith("1,E-001,"), lines[1]);
        assertTrue(lines[2].startsWith("2,,,"), "unnamed without opt-in: " + lines[2]);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE academy_id = ? AND action = 'ENGAGEMENT_RANKING_EXPORTED'",
                Integer.class, academyId));
        assertEquals(403, api.get("/api/v1/courses/" + course + "/engagement-ranking/export" + range, studentTokens.get("1")).statusCode());
        assertEquals(403, api.get(path, studentTokens.get("1")).statusCode());
        assertEquals(403, api.get(path, tokenFor(academyId, Role.STUDENT)).statusCode());
    }

    private static String toJson(Object value) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
