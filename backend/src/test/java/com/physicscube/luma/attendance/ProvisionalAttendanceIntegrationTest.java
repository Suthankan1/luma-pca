package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import com.physicscube.luma.zoom.ZoomReceiptProcessor;
import com.physicscube.luma.zoom.ZoomTestReceipts;
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
import org.springframework.context.ApplicationContext;

/** Provisional attendance from live Zoom events (004A). Made-up values in the shape of the 13 Nov test webinar. */
class ProvisionalAttendanceIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";
    private static final String OCCURRENCE = "Class/One==";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;

    UUID academyId;
    String admin;
    String batch;
    String session;
    Map<String, String> ids = new LinkedHashMap<>();

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        for (String n : List.of("1", "2", "3", "4")) {
            ids.put(n, (String) fixtures.student("S-00" + n, "student" + n + "@example.test").get("id"));
            if (!n.equals("3")) {
                assertEquals(201, fixtures.enroll(batch, ids.get(n), "2026-01-01").statusCode());
            }
        }
        // 08:30–10:30 UTC on a past date, so a live visit without a leave is counted to the class end.
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Optics\",\"startsAt\":\"2026-02-02T08:30:00Z\","
                + "\"endsAt\":\"2026-02-02T10:30:00Z\",\"batchIds\":[\"" + batch + "\"],\"zoomWebinarId\":\"" + WEBINAR + "\"}", admin);
        assertEquals(201, created.statusCode(), created.body());
        session = (String) HttpApi.parse(created).get("id");
    }

    private void event(String type, String at, String participantJson) {
        event(type, Instant.parse("2026-02-02T" + at + ":00Z"), participantJson);
    }

    private void event(String type, Instant at, String participantJson) {
        long ts = at.toEpochMilli();
        String object = "{\"id\":\"" + WEBINAR + "\",\"uuid\":\"" + OCCURRENCE + "\",\"host_id\":\"hostuser\""
                + (participantJson == null ? "" : ",\"participant\":" + participantJson) + "}";
        String json = "{\"event\":\"" + type + "\",\"event_ts\":" + ts + ",\"payload\":{\"object\":" + object + "}}";
        ZoomTestReceipts.store(context, academyId, type, ts, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void studentLiveAttendanceIsScopedAndAvailableOnlyDuringTheClass() {
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        UUID owner = accountIdOf("live-staff@example.test", academyId, Role.STAFF);
        var mine = fixtures.student("LIVE-01", "live-student@example.test");
        var outsider = fixtures.student("LIVE-02", "outsider@example.test");
        activate(UUID.fromString((String) mine.get("accountId")));
        activate(UUID.fromString((String) outsider.get("accountId")));
        String viewer = login("live-student@example.test");
        String unrelated = login("outsider@example.test");
        UUID otherAcademy = academy("Different academy");
        String foreign = tokenFor(otherAcademy, Role.STUDENT);
        Instant now = Instant.now();
        String date = now.minusSeconds(86400).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        assertEquals(201, fixtures.enroll(batch, (String) mine.get("id"), date).statusCode());
        String otherBatch = fixtures.batch(fixtures.course("LIVE"), "OTHER");
        assertEquals(201, fixtures.enroll(otherBatch, (String) outsider.get("id"), date).statusCode());

        // The monitor cache and class mapping are fixtures. Reading them must make no Zoom API call.
        jdbc.update("""
                INSERT INTO academy_class_links (id, academy_id, class_name, batch_id, linked_by)
                VALUES (?, ?, 'Upcoming Physics', ?, ?)
                """, UUID.randomUUID(), academyId, UUID.fromString(batch), owner);
        jdbc.update("""
                INSERT INTO zoom_webinar_monitors (id, academy_id, webinar_id, topic, time_zone,
                    scheduled_start_at, duration_minutes, sync_status, academy_class_name, class_source, added_by, added_at)
                VALUES (?, ?, '89900000000', 'Upcoming Physics', 'Asia/Colombo', ?, 60, 'OK',
                    'Upcoming Physics', 'ADMIN', ?, now())
                """, UUID.randomUUID(), academyId, java.sql.Timestamp.from(now.plusSeconds(3600)), owner);
        String schedule = "/api/v1/me/webinar-schedule?from=" + now + "&to=" + now.plusSeconds(7200);
        assertEquals(200, api.get(schedule, viewer).statusCode());
        org.junit.jupiter.api.Assertions.assertTrue(api.get(schedule, viewer).body().contains("Upcoming Physics"));
        assertEquals("[]", api.get(schedule, unrelated).body());
        assertEquals(403, api.get(schedule, admin).statusCode());
        assertEquals(401, api.get(schedule, null).statusCode());

        HttpResponse<String> created = api.post("/api/v1/class-sessions", """
                {"title":"Current physics","startsAt":"%s","endsAt":"%s","batchIds":["%s","%s"],"zoomWebinarId":"%s"}
                """.formatted(now.minusSeconds(600), now.plusSeconds(3600), batch, otherBatch, WEBINAR), admin);
        assertEquals(201, created.statusCode(), created.body());
        String path = "/api/v1/me/class-sessions/" + HttpApi.parse(created).get("id") + "/live-attendance";
        assertEquals(404, api.get(path, viewer).statusCode(), "a scheduled window is not live evidence");
        assertEquals(401, api.get(path, null).statusCode());
        assertEquals(403, api.get(path, admin).statusCode());

        event("webinar.started", now.minusSeconds(600), null);
        event("webinar.participant_joined", now.minusSeconds(300),
                "{\"participant_uuid\":\"viewer-a\",\"email\":\"live-student@example.test\"}");
        event("webinar.participant_joined", now.minusSeconds(250),
                "{\"participant_uuid\":\"viewer-b\",\"email\":\"live-student@example.test\"}");
        event("webinar.participant_joined", now.minusSeconds(200),
                "{\"participant_uuid\":\"other\",\"email\":\"outsider@example.test\"}");
        processor.runOnce();

        var live = api.get(path, viewer);
        assertEquals(200, live.statusCode(), live.body());
        var data = HttpApi.parse(live);
        assertEquals(1, data.get("joined"), "deduplicate overlapping visits and omit other batches");
        assertEquals(1, data.get("online"));
        assertEquals(true, data.get("seen"));
        assertFalse(live.body().contains("outsider"));
        assertEquals(404, api.get(path, foreign).statusCode());
        assertEquals(404, api.get("/api/v1/me/class-sessions/" + session + "/live-attendance", viewer).statusCode());

        event("webinar.ended", now.minusSeconds(5), null);
        processor.runOnce();
        assertEquals(404, api.get(path, viewer).statusCode());
    }

    private void visit(String type, String at, String visit, String email) {
        event(type, at, "{\"id\":\"" + (email.startsWith("teacher") ? "hostuser" : "") + "\",\"participant_uuid\":\"" + visit
                + "\",\"user_id\":\"1\",\"user_name\":\"Someone\",\"email\":\"" + email + "\"}");
    }

    private void runClass(boolean ended) {
        event("webinar.started", "08:25", null);
        visit("webinar.participant_joined", "08:25", "host", "teacher@example.test");
        // Student 1: early, leaves and rejoins with overlap: 08:30–09:30 inside the class.
        visit("webinar.participant_joined", "08:25", "s1a", "student1@example.test");
        visit("webinar.participant_left", "09:00", "s1a", "student1@example.test");
        visit("webinar.participant_joined", "08:55", "s1b", "Student1@Example.test");
        visit("webinar.participant_left", "09:30", "s1b", "student1@example.test");
        // Student 2: 15 minutes late, no leave received.
        visit("webinar.participant_joined", "08:45", "s2", "student2@example.test");
        // Student 3: not in the class's batch.
        visit("webinar.participant_joined", "09:00", "s3", "student3@example.test");
        visit("webinar.participant_left", "09:10", "s3", "student3@example.test");
        // Someone matched to no student.
        visit("webinar.participant_joined", "09:05", "g", "guest@example.test");
        if (ended) {
            event("webinar.ended", "10:00", null);
        }
        processor.runOnce();
    }

    private Map<String, Object> attendance(String token) {
        return attendance(token, false);
    }

    private String attendancePath(boolean onlineHistory) {
        return "/api/v1/class-sessions/" + session + "/attendance" + (onlineHistory ? "?includeOnlineHistory=true" : "");
    }

    private Map<String, Object> attendance(String token, boolean onlineHistory) {
        HttpResponse<String> response = api.get(attendancePath(onlineHistory), token);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> onlineHistory(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("onlineHistory");
    }

    private static Map<String, Object> sample(String time, int online) {
        return Map.of("at", "2026-02-02T" + time + "Z", "online", online);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> byNumber(Map<String, Object> body) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Object row : (List<Object>) body.get("students")) {
            Map<String, Object> r = (Map<String, Object>) row;
            rows.put((String) r.get("studentNumber"), r);
        }
        return rows;
    }

    private static long seconds(Map<String, Object> row) {
        return ((Number) row.get("connectedSeconds")).longValue();
    }

    @Test
    void connectedTimeMergesRejoinsClipsToTheClassAndNeverDecidesAbsence() {
        runClass(true);
        Map<String, Object> body = attendance(admin);
        assertEquals("PROVISIONAL", body.get("status"));
        assertEquals(1, body.get("linkedOccurrences"));
        assertEquals(true, body.get("webinarEnded"));
        assertEquals(1, body.get("unmatchedPeople"), "the guest; the host is never counted");

        Map<String, Map<String, Object>> rows = byNumber(body);
        assertEquals(List.of("S-001", "S-002", "S-004", "S-003"), List.copyOf(rows.keySet()), "expected students first");

        Map<String, Object> first = rows.get("S-001");
        assertEquals(3600, seconds(first), "08:25–09:30 with an overlapping rejoin, counted from 08:30");
        assertEquals(2, first.get("visits"));
        assertEquals(0, first.get("openVisits"));
        assertEquals("2026-02-02T08:25:00Z", first.get("firstJoinAt"));
        assertEquals(0, ((Number) first.get("secondsAfterStart")).intValue(), "early is not late");

        Map<String, Object> late = rows.get("S-002");
        assertEquals(900, ((Number) late.get("secondsAfterStart")).intValue());
        assertEquals(1, late.get("openVisits"));
        assertEquals(4500, seconds(late), "no leave: counted to the webinar's end (10:00)");

        Map<String, Object> notSeen = rows.get("S-004");
        assertEquals(false, notSeen.get("seen"));
        assertEquals(0, seconds(notSeen));
        assertNull(notSeen.get("firstJoinAt"));

        Map<String, Object> extra = rows.get("S-003");
        assertEquals(false, extra.get("expected"));
        assertEquals(600, seconds(extra));
    }

    @Test
    void anOpenVisitRunsToTheClassEndWhileTheWebinarHasNotEnded() {
        runClass(false);
        Map<String, Object> body = attendance(admin);
        assertEquals(false, body.get("webinarEnded"));
        assertEquals(6300, seconds(byNumber(body).get("S-002")), "08:45 to the class end at 10:30 (the date is past)");
    }

    @Test
    void aClassWithoutLinkedZoomOccurrencesListsExpectedStudentsUnseen() {
        Map<String, Object> body = attendance(admin);
        assertEquals(0, body.get("linkedOccurrences"));
        assertEquals(false, body.get("webinarEnded"));
        Map<String, Map<String, Object>> rows = byNumber(body);
        assertEquals(List.of("S-001", "S-002", "S-004"), List.copyOf(rows.keySet()));
        rows.values().forEach(row -> assertEquals(false, row.get("seen")));
        assertFalse(body.containsKey("onlineHistory"));
        assertEquals(List.of(), onlineHistory(attendance(admin, true)));
    }

    @Test
    void onlineHistoryStartsWithAnObservedJoinAndDoesNotBackfillZeros() {
        event("webinar.started", "08:30", null);
        visit("webinar.participant_left", "08:35", "leave-only", "student1@example.test");
        visit("webinar.participant_joined", "08:45", "s2", "student2@example.test");
        visit("webinar.participant_left", "08:46", "s2", "student2@example.test");
        event("webinar.ended", "08:47", null);
        processor.runOnce();

        assertEquals(List.of(sample("08:45:00", 1), sample("08:45:30", 1), sample("08:46:00", 0),
                sample("08:46:30", 0), sample("08:47:00", 0)), onlineHistory(attendance(admin, true)));
    }

    @Test
    void everyStaffMemberSeesTheWholeClassAndOtherAcademiesNothing() {
        runClass(true);
        String colleague = tokenFor(academyId, Role.STAFF);
        assertEquals(List.copyOf(byNumber(attendance(admin)).keySet()), List.copyOf(byNumber(attendance(colleague)).keySet()),
                "no batch assignments: every staff member sees the same rows, including students seen but not expected");

        List<Map<String, Object>> history = onlineHistory(attendance(colleague, true));
        assertEquals(onlineHistory(attendance(admin, true)), history);
        assertEquals(sample("08:30:00", 1), history.getFirst(), "early student's visit clips to class start");
        assertEquals(sample("08:55:00", 2), history.get(50), "overlapping visits count as one student");
        assertEquals(sample("09:05:00", 3), history.get(70), "matched extra student is counted, guest and host are not");
        assertEquals(sample("09:50:00", 1), history.get(160), "open visit lasts to the webinar's end");
        assertEquals(sample("10:00:00", 0), history.getLast());

        assertEquals(401, api.get(attendancePath(true), null).statusCode());
        String student = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, api.get(attendancePath(true), student).statusCode());
        String otherAcademy = tokenFor(academy("Other Academy"), Role.STAFF);
        assertEquals(404, api.get(attendancePath(true), otherAcademy).statusCode());
    }
}
