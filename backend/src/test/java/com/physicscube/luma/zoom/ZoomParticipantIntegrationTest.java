package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Participant identity (003D part 2). Payloads follow the field shapes of the first real test
 * webinar (13 Nov 2026); every value here is made up.
 */
class ZoomParticipantIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "81234567890";
    private static final String OCCURRENCE = "Lesson/One==";
    private static final String HOST_ID = "hostuser";

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ZoomWebhookReceipts receipts;

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batch;
    String enrolled;
    String notEnrolled;
    String session;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.STAFF);
        fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "A");
        enrolled = (String) fixtures.student("S-001", "first.student@example.test").get("id");
        notEnrolled = (String) fixtures.student("S-002", "second.student@example.test").get("id");
        assertEquals(201, fixtures.enroll(batch, enrolled, "2027-01-01").statusCode());
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Lesson\",\"startsAt\":\"2027-03-01T08:30:00Z\","
                + "\"endsAt\":\"2027-03-01T10:30:00Z\",\"batchIds\":[\"" + batch + "\"],\"zoomWebinarId\":\"" + WEBINAR + "\"}", admin);
        assertEquals(201, created.statusCode(), created.body());
        session = (String) HttpApi.parse(created).get("id");
    }

    private void webinar(String type, String at) {
        store(type, at, "{\"id\":\"" + WEBINAR + "\",\"uuid\":\"" + OCCURRENCE + "\",\"host_id\":\"" + HOST_ID + "\"}");
    }

    /** A joined or left event; {@code email} may be null. */
    private void participant(String type, String at, String id, String visit, String email, String name) {
        StringBuilder p = new StringBuilder("{\"id\":\"").append(id).append("\",\"user_id\":\"").append(visit.hashCode())
                .append("\",\"participant_uuid\":\"").append(visit).append("\",\"registrant_id\":\"reg-").append(id)
                .append("\",\"user_name\":\"").append(name).append("\",\"public_ip\":\"192.0.2.10\"");
        if (email != null) {
            p.append(",\"email\":\"").append(email).append('"');
        }
        p.append(type.endsWith("joined") ? ",\"join_time\":\"" + at + "\"" : ",\"leave_time\":\"" + at + "\",\"leave_reason\":\"left\"");
        store(type, at, "{\"id\":\"" + WEBINAR + "\",\"uuid\":\"" + OCCURRENCE + "\",\"host_id\":\"" + HOST_ID
                + "\",\"participant\":" + p.append('}') + "}");
    }

    private void store(String type, String at, String object) {
        long ts = Instant.parse(at).toEpochMilli();
        String json = "{\"event\":\"" + type + "\",\"event_ts\":" + ts + ",\"payload\":{\"account_id\":\"acct\",\"object\":" + object + "}}";
        receipts.store(academyId, type, ts, json.getBytes(StandardCharsets.UTF_8));
    }

    private void runWebinar() {
        webinar("webinar.started", "2027-03-01T08:31:00Z");
        participant("webinar.participant_joined", "2027-03-01T08:31:01Z", HOST_ID, "visit-host", "teacher@example.test", "Teacher");
        participant("webinar.participant_joined", "2027-03-01T08:32:00Z", "", "visit-a1", "first.student@example.test", "First");
        participant("webinar.participant_left", "2027-03-01T08:40:00Z", "", "visit-a1", "first.student@example.test", "First");
        // Rejoin: a new visit, the same person; Zoom's letter case is not trusted.
        participant("webinar.participant_joined", "2027-03-01T08:41:00Z", "", "visit-a2", "First.Student@Example.TEST", "First S");
        participant("webinar.participant_joined", "2027-03-01T08:33:00Z", "", "visit-b", "second.student@example.test", "Second");
        participant("webinar.participant_joined", "2027-03-01T08:34:00Z", "", "visit-g", "guest@example.test", "Guest");
        participant("webinar.participant_left", "2027-03-01T08:50:00Z", "", "visit-g", "guest@example.test", "Guest");
        participant("webinar.participant_joined", "2027-03-01T08:35:00Z", "", "visit-x", null, "No Email");
        processor.runOnce();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> people() {
        HttpResponse<String> response = api.get("/api/v1/zoom/participants?occurrence="
                + URLEncoder.encode(OCCURRENCE, StandardCharsets.UTF_8), admin);
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> body = HttpApi.parse(response);
        assertEquals(session, body.get("classSessionId"));
        return (List<Map<String, Object>>) body.get("participants");
    }

    private static Map<String, Object> byEmail(List<Map<String, Object>> people, String email) {
        return people.stream().filter(p -> java.util.Objects.equals(email, p.get("email"))).findFirst().orElseThrow();
    }

    private HttpResponse<String> decide(String email, String studentId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("studentId", studentId);
        body.put("reason", reason);
        return api.post("/api/v1/zoom/identity-links", HttpApi.json(body), admin);
    }

    @Test
    void peopleAreGroupedByEmailAcrossRejoinsAndMatchedBySignInEmail() {
        runWebinar();
        List<Map<String, Object>> people = people();
        assertEquals(5, people.size());

        Map<String, Object> first = byEmail(people, "first.student@example.test");
        assertEquals(2, first.get("visits"));
        assertEquals(1, first.get("openVisits"), "the rejoin has no leave yet");
        assertEquals("STUDENT", first.get("match"));
        assertEquals("SIGN_IN_EMAIL", first.get("matchSource"));
        assertEquals(enrolled, first.get("studentId"));
        assertEquals("S-001", first.get("studentNumber"));
        assertEquals(true, first.get("expected"));
        assertEquals("First S", first.get("displayName"), "the latest name Zoom sent");
        assertEquals("2027-03-01T08:32:00Z", first.get("firstSeenAt"));
        assertEquals("2027-03-01T08:41:00Z", first.get("lastSeenAt"));

        Map<String, Object> second = byEmail(people, "second.student@example.test");
        assertEquals("STUDENT", second.get("match"));
        assertEquals(false, second.get("expected"), "a student, but not in this class's batches");

        assertEquals("UNMATCHED", byEmail(people, "guest@example.test").get("match"));
        assertEquals(0, byEmail(people, "guest@example.test").get("openVisits"));
        Map<String, Object> noEmail = byEmail(people, null);
        assertEquals("UNMATCHED", noEmail.get("match"));
        assertEquals("No Email", noEmail.get("displayName"));
        Map<String, Object> host = byEmail(people, "teacher@example.test");
        assertEquals("HOST", host.get("match"));
        assertNull(host.get("expected"));

        // Unmatched people come first, the host last.
        assertEquals("UNMATCHED", people.get(0).get("match"));
        assertEquals("HOST", people.get(people.size() - 1).get("match"));

        // The stored copy keeps who it was, never the IP address.
        List<Map<String, Object>> stored = jdbc.queryForList("SELECT * FROM zoom_occurrence_events WHERE academy_id = ? "
                + "AND visit_key IS NOT NULL", academyId);
        assertEquals(8, stored.size());
        assertTrue(stored.stream().noneMatch(row -> row.toString().contains("192.0.2.10")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_webhook_receipts WHERE academy_id = ? AND payload::text LIKE '%192.0.2.10%'",
                Integer.class, academyId), "receipts are stored without the IP address");
        assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM zoom_webhook_receipts WHERE academy_id = ? "
                + "AND payload #>> '{payload,object,participant,email}' IS NOT NULL OR (academy_id = ? AND payload #>> '{payload,object,participant,user_name}' = 'No Email')",
                Integer.class, academyId, academyId), "everything else is kept");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zoom_occurrence_events WHERE academy_id = ? AND visit_key IS NULL",
                Integer.class, academyId), "webinar.started has no visit");
    }

    @Test
    @SuppressWarnings("unchecked")
    void decisionsOverrideTheSignInMatchAndLeaveTheReviewList() {
        runWebinar();
        HttpResponse<String> unmatched = api.get("/api/v1/zoom/participants/unmatched", admin);
        assertEquals(200, unmatched.statusCode(), unmatched.body());
        List<Map<String, Object>> review = (List<Map<String, Object>>) (Object) HttpApi.parseList(unmatched);
        assertEquals(List.of("guest@example.test"), review.stream().map(r -> r.get("email")).toList(),
                "students and the host are not listed; nor is a visitor without email");
        assertEquals("Guest", review.get(0).get("displayName"));
        assertEquals(1, review.get(0).get("occurrences"));

        HttpResponse<String> linked = decide("Guest@Example.test", notEnrolled, "Uses a personal Zoom account");
        assertEquals(200, linked.statusCode(), linked.body());
        assertEquals("S-002", HttpApi.parse(linked).get("studentNumber"));
        assertEquals(200, decide("first.student@example.test", null, "Shared device used by a parent").statusCode());

        List<Map<String, Object>> people = people();
        Map<String, Object> guest = byEmail(people, "guest@example.test");
        assertEquals("STUDENT", guest.get("match"));
        assertEquals("MANUAL", guest.get("matchSource"));
        assertEquals(notEnrolled, guest.get("studentId"));
        Map<String, Object> first = byEmail(people, "first.student@example.test");
        assertEquals("NOT_A_STUDENT", first.get("match"), "a decision overrides the sign-in email");
        assertNull(first.get("studentId"));
        assertNull(first.get("expected"));

        assertEquals(List.of(), HttpApi.parseList(api.get("/api/v1/zoom/participants/unmatched", admin)));
        List<Object> decisions = HttpApi.parseList(api.get("/api/v1/zoom/identity-links", admin));
        assertEquals(2, decisions.size());

        // Changing a decision replaces it, and every decision is audited.
        assertEquals(200, decide("guest@example.test", null, "Was a visiting teacher").statusCode());
        assertEquals("NOT_A_STUDENT", byEmail(people(), "guest@example.test").get("match"));
        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, target_id::text AS target, details::text AS details "
                + "FROM audit_events WHERE academy_id = ? AND action LIKE 'ZOOM_IDENTITY%' ORDER BY occurred_at", academyId);
        assertEquals(List.of("ZOOM_IDENTITY_LINKED", "ZOOM_IDENTITY_MARKED_NOT_STUDENT", "ZOOM_IDENTITY_MARKED_NOT_STUDENT"),
                audit.stream().map(a -> a.get("action")).toList());
        assertEquals(notEnrolled, audit.get(0).get("target"));
        assertTrue(((String) audit.get(2).get("details")).contains("\"previous\": \"" + notEnrolled + "\""), audit.get(2).toString());
    }

    @Test
    void decisionsAreValidatedAndLimitedToStaffOfTheAcademy() {
        runWebinar();
        assertEquals(404, decide("nobody@example.test", null, "Never joined").statusCode(), "only emails seen in Zoom");
        assertEquals(400, decide("guest@example.test", null, "  ").statusCode());
        assertEquals(400, decide("not-an-email", null, "Reason here").statusCode());

        UUID other = academy("Other Academy");
        String otherStudent = (String) new AcademyFixtures(api, tokenFor(other, Role.STAFF))
                .student("O-001", "other.student@example.test").get("id");
        assertEquals(404, decide("guest@example.test", otherStudent, "Wrong academy").statusCode());

        String nonStaff = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, api.get("/api/v1/zoom/participants/unmatched", nonStaff).statusCode());
        assertEquals(403, api.get("/api/v1/zoom/participants?occurrence=x", nonStaff).statusCode());
        assertEquals(404, api.get("/api/v1/zoom/participants?occurrence=unknown", admin).statusCode());
        // Another academy's admin sees none of this academy's occurrences or people.
        String otherAdmin = tokenFor(other, Role.STAFF);
        assertEquals(404, api.get("/api/v1/zoom/participants?occurrence=" + URLEncoder.encode(OCCURRENCE, StandardCharsets.UTF_8),
                otherAdmin).statusCode());
        assertEquals(List.of(), HttpApi.parseList(api.get("/api/v1/zoom/participants/unmatched", otherAdmin)));
    }

    @Test
    void aDecisionCanBeRemovedSoTheAutomaticMatchAppliesAgain() {
        runWebinar();
        assertEquals(200, decide("first.student@example.test", null, "Thought it was a parent").statusCode());
        assertEquals("NOT_A_STUDENT", byEmail(people(), "first.student@example.test").get("match"));
        assertEquals(400, api.post("/api/v1/zoom/identity-links/removals", "{\"email\":\"first.student@example.test\",\"reason\":\"\"}", admin)
                .statusCode());
        assertEquals(204, api.post("/api/v1/zoom/identity-links/removals",
                "{\"email\":\"First.Student@example.test\",\"reason\":\"It was the student after all\"}", admin).statusCode());
        Map<String, Object> first = byEmail(people(), "first.student@example.test");
        assertEquals("STUDENT", first.get("match"));
        assertEquals("SIGN_IN_EMAIL", first.get("matchSource"));
        assertEquals(404, api.post("/api/v1/zoom/identity-links/removals",
                "{\"email\":\"first.student@example.test\",\"reason\":\"Again\"}", admin).statusCode(), "nothing left to remove");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE academy_id = ? AND action = 'ZOOM_IDENTITY_DECISION_REMOVED'",
                Integer.class, academyId));
    }

    @Test
    void someoneWhoJoinedFromAnotherZoomAccountIsMatchedByTheirRegistrationEmail() {
        jdbc.update("INSERT INTO zoom_registrants (academy_id, webinar_id, registrant_id, email, fetched_at) VALUES (?, ?, 'reg-', "
                + "'second.student@example.test', now())", academyId, WEBINAR);
        runWebinar();
        Map<String, Object> guest = byEmail(people(), "guest@example.test");
        assertEquals("STUDENT", guest.get("match"), "the guest's own email matches nobody; its registration email is student 2's");
        assertEquals("REGISTRATION_EMAIL", guest.get("matchSource"));
        assertEquals("S-002", guest.get("studentNumber"));
    }
}
