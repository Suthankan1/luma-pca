package com.physicscube.luma.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Erasure on request, retention, and changing one's own password. */
class PrivacyIntegrationTest extends StaffApiIntegrationTest {
    @Autowired
    RetentionJob retention;

    UUID academyId;
    String owner;
    String admin;
    String studentId;
    UUID accountId;
    String session;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        owner = tokenFor(academyId, Role.STAFF);
        admin = tokenFor(academyId, Role.STAFF);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String batch = fixtures.batch(fixtures.course("PHY-2026"), "A");
        Map<String, Object> student = fixtures.student("S-001", "erase.me@example.test");
        studentId = (String) student.get("id");
        accountId = UUID.fromString((String) student.get("accountId"));
        activate(accountId);
        assertEquals(201, fixtures.enroll(batch, studentId, "2026-01-01").statusCode());
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Optics\",\"startsAt\":\"2026-02-02T08:30:00Z\","
                + "\"endsAt\":\"2026-02-02T10:30:00Z\",\"batchIds\":[\"" + batch + "\"]}", admin);
        session = (String) HttpApi.parse(created).get("id");
    }

    private UUID receipt(String email, Instant receivedAt, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO zoom_webhook_receipts (id, academy_id, event_type, event_ts, body_sha256, payload, received_at, processing_status, "
                + "processed_at) VALUES (?, ?, 'webinar.participant_joined', 1, ?, ?::jsonb, ?, ?, ?)", id, academyId,
                String.format("%064x", id.getLeastSignificantBits() & Long.MAX_VALUE),
                "{\"payload\":{\"object\":{\"id\":\"81234567890\",\"uuid\":\"Occ==\",\"participant\":{\"email\":\"" + email
                        + "\",\"user_name\":\"Erase Me\",\"registrant_id\":\"reg-1\",\"participant_uuid\":\"v\"}}}}",
                Timestamp.from(receivedAt), status, "PROCESSED".equals(status) ? Timestamp.from(receivedAt) : null);
        return id;
    }

    @Test
    void staffEraseAStudentsPersonalDataKeepingAnonymisedAttendance() {
        UUID receipt = receipt("erase.me@example.test", Instant.now(), "PROCESSED");
        jdbc.update("INSERT INTO zoom_occurrence_events (id, academy_id, receipt_id, webinar_id, occurrence_uuid, event_type, event_ts, created_at, "
                + "visit_key, participant_email, participant_name) VALUES (?, ?, ?, '81234567890', 'Occ==', 'webinar.participant_joined', 1, now(), "
                + "'v', 'erase.me@example.test', 'Erase Me')", UUID.randomUUID(), academyId, receipt);
        jdbc.update("INSERT INTO zoom_identity_links (academy_id, email, student_id, reason, linked_by, linked_at) VALUES (?, 'other.zoom@example.test', "
                + "?::uuid, 'Second account', ?, now())", academyId, studentId, UUID.randomUUID());
        jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, fetched_at, join_sessions) "
                + "VALUES (?, 'Occ==', 'FETCHED', 1, now(), 1)", academyId);
        jdbc.update("INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, display_name, joined_at, left_at, duration_seconds) "
                + "VALUES (?, ?, 'Occ==', 'other.zoom@example.test', 'Erase Me', now(), now(), 0)", UUID.randomUUID(), academyId);
        jdbc.update("INSERT INTO attendance_records (id, academy_id, class_session_id, student_id, computed_outcome, outcome, connected_seconds, "
                + "rule_min_connected_percent, rule_late_after_minutes, finalised_by, finalised_at) VALUES (?, ?, ?::uuid, ?::uuid, 'PRESENT', "
                + "'PRESENT', 7200, 75, 10, ?, now())", UUID.randomUUID(), academyId, session, studentId, UUID.randomUUID());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("confirmStudentNumber", "S-999");
        body.put("reason", "Request from the student's guardian");
        String path = "/api/v1/students/" + studentId + "/erasure";
        assertEquals(403, api.post(path, HttpApi.json(body), tokenFor(academyId, Role.STUDENT)).statusCode(), "staff only");
        assertEquals(400, api.post(path, HttpApi.json(body), owner).statusCode(), "the number must be typed again");
        body.put("confirmStudentNumber", "s-001");
        HttpResponse<String> erased = api.post(path, HttpApi.json(body), owner);
        assertEquals(200, erased.statusCode(), erased.body());
        String placeholder = (String) HttpApi.parse(erased).get("placeholderNumber");
        assertTrue(placeholder.startsWith("ERASED-"));

        Map<String, Object> record = jdbc.queryForMap("SELECT s.full_name, s.student_number, s.active, a.email, a.status FROM students s "
                + "JOIN accounts a ON a.id = s.account_id WHERE s.id = ?::uuid", studentId);
        assertEquals("Erased student", record.get("full_name"));
        assertEquals(placeholder, record.get("student_number"));
        assertEquals(false, record.get("active"));
        assertTrue(((String) record.get("email")).endsWith("@erased.invalid"));
        assertEquals("DISABLED", record.get("status"));
        assertEquals(401, passwordLogin("erase.me@example.test").statusCode());

        assertNull(jdbc.queryForObject("SELECT participant_email FROM zoom_occurrence_events WHERE academy_id = ?", String.class, academyId));
        assertFalse(jdbc.queryForObject("SELECT payload::text FROM zoom_webhook_receipts WHERE id = ?", String.class, receipt).contains("erase.me"));
        assertNull(jdbc.queryForObject("SELECT email FROM zoom_report_participants WHERE academy_id = ?", String.class, academyId),
                "the second Zoom account, known from a staff decision, is cleared too");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_identity_links WHERE academy_id = ?", Integer.class, academyId));
        assertEquals("PRESENT", jdbc.queryForObject("SELECT outcome FROM attendance_records WHERE student_id = ?::uuid", String.class, studentId),
                "attendance figures stay, anonymised");
        String details = jdbc.queryForObject("SELECT details::text FROM audit_events WHERE action = 'STUDENT_ERASED' AND academy_id = ?",
                String.class, academyId);
        assertFalse(details.contains("erase.me"), "the audit entry holds no personal data");
    }

    @Test
    void retentionScrubsOldProcessedPayloadsOnly() {
        UUID old = receipt("old@example.test", Instant.now().minus(200, ChronoUnit.DAYS), "PROCESSED");
        UUID recent = receipt("recent@example.test", Instant.now().minus(10, ChronoUnit.DAYS), "PROCESSED");
        UUID dead = receipt("dead@example.test", Instant.now().minus(200, ChronoUnit.DAYS), "DEAD");
        assertTrue(retention.runOnce() >= 1);
        assertFalse(jdbc.queryForObject("SELECT payload::text FROM zoom_webhook_receipts WHERE id = ?", String.class, old).contains("old@example"));
        assertTrue(jdbc.queryForObject("SELECT payload::text FROM zoom_webhook_receipts WHERE id = ?", String.class, old).contains("participant_uuid"),
                "the rest of the event stays");
        assertTrue(jdbc.queryForObject("SELECT payload::text FROM zoom_webhook_receipts WHERE id = ?", String.class, recent).contains("recent@example"));
        assertTrue(jdbc.queryForObject("SELECT payload::text FROM zoom_webhook_receipts WHERE id = ?", String.class, dead).contains("dead@example"),
                "failed events keep what is needed to replay them");
    }

    @Test
    void peopleChangeTheirOwnPasswordWithTheCurrentOneAndAreSignedOutEverywhere() {
        String student = login("erase.me@example.test");
        assertEquals(400, api.post("/api/v1/auth/password", "{\"currentPassword\":\"not it at all\",\"newPassword\":\"another long passphrase\"}",
                student).statusCode());
        assertEquals(422, api.post("/api/v1/auth/password", "{\"currentPassword\":\"quiet river under bridge\",\"newPassword\":\"short\"}",
                student).statusCode(), "the password policy applies");
        assertEquals(204, api.post("/api/v1/auth/password",
                "{\"currentPassword\":\"quiet river under bridge\",\"newPassword\":\"a brand new long passphrase\"}", student).statusCode());
        assertEquals(401, api.get("/api/v1/auth/me", student).statusCode(), "every session ends");
        assertEquals(401, passwordLogin("erase.me@example.test").statusCode(), "the old password no longer works");
        HttpResponse<String> fresh = api.post("/api/v1/auth/login",
                "{\"email\":\"erase.me@example.test\",\"password\":\"a brand new long passphrase\"}");
        assertEquals(200, fresh.statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PASSWORD_CHANGED' AND academy_id = ?",
                Integer.class, academyId));
        assertEquals(401, api.post("/api/v1/auth/password", "{\"currentPassword\":\"x\",\"newPassword\":\"y\"}").statusCode(), "signed-in only");
    }
}
