package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Attendance over a period: per student for a batch, per batch for the academy (analysts included, without names). */
class AttendanceSummaryIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String admin;
    String batchA;
    String batchB;
    Map<String, String> students = new LinkedHashMap<>();

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String course = fixtures.course("PHY-2026");
        batchA = fixtures.batch(course, "A");
        batchB = fixtures.batch(course, "B");
        for (String n : List.of("1", "2", "3", "4")) {
            students.put("S-00" + n, (String) fixtures.student("S-00" + n, "s" + n + "@example.test").get("id"));
        }
        for (String n : List.of("S-001", "S-002", "S-003")) {
            assertEquals(201, fixtures.enroll(batchA, students.get(n), "2026-01-01").statusCode());
        }
        assertEquals(201, fixtures.enroll(batchB, students.get("S-003"), "2026-01-01").statusCode()); // in both batches
        assertEquals(201, fixtures.enroll(batchB, students.get("S-004"), "2026-01-01").statusCode());

        // March in Sri Lanka: two final classes for A and B, one ended but not final, one cancelled.
        String first = session("2026-03-02T13:00:00Z", batchA, batchB);
        String second = session("2026-03-09T13:00:00Z", batchA, batchB);
        session("2026-03-16T13:00:00Z", batchA);
        String cancelled = session("2026-03-23T13:00:00Z", batchA);
        jdbc.update("UPDATE class_sessions SET status = 'CANCELLED' WHERE id = ?::uuid", cancelled);
        // 31 Mar 20:00 UTC is 1 Apr in Colombo: April, not March.
        String april = session("2026-03-31T20:00:00Z", batchA);
        record(first, "S-001", "PRESENT");
        record(first, "S-002", "ABSENT");
        record(first, "S-003", "LATE");
        record(first, "S-004", "PRESENT");
        record(second, "S-001", "PRESENT");
        record(second, "S-002", "EXCUSED");
        record(second, "S-003", "ABSENT");
        record(second, "S-004", "ABSENT");
        record(april, "S-001", "ABSENT");
    }

    private String session(String startsAt, String... batches) {
        String end = Instant.parse(startsAt).plusSeconds(7200).toString();
        HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Class " + startsAt + "\",\"startsAt\":\"" + startsAt
                + "\",\"endsAt\":\"" + end + "\",\"batchIds\":[\"" + String.join("\",\"", batches) + "\"]}", admin);
        assertEquals(201, created.statusCode(), created.body());
        return (String) HttpApi.parse(created).get("id");
    }

    private void record(String session, String student, String outcome) {
        String computed = outcome.equals("EXCUSED") ? "ABSENT" : outcome;
        jdbc.update("INSERT INTO attendance_records (id, academy_id, class_session_id, student_id, computed_outcome, outcome, connected_seconds, "
                + "rule_min_connected_percent, rule_late_after_minutes, finalised_by, finalised_at) VALUES (?, ?, ?::uuid, ?::uuid, ?, ?, 0, 75, 10, ?, ?)",
                UUID.randomUUID(), academyId, session, students.get(student), computed, outcome, UUID.randomUUID(), Timestamp.from(Instant.now()));
    }

    private static Map<String, Object> counts(Map<String, Object> holder) {
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) holder.get("counts");
        return counts != null ? counts : asMap(holder.get("totals"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBatchShowsEachStudentsRateLowestFirst() {
        HttpResponse<String> response = api.get("/api/v1/batches/" + batchA + "/attendance/summary?from=2026-03-01&to=2026-03-31", admin);
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> summary = HttpApi.parse(response);
        assertEquals(2, summary.get("finalClasses"));
        assertEquals(1, summary.get("classesNotFinal"), "ended, not final; the cancelled class and April are not counted");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("students");
        assertEquals(List.of("S-002", "S-003", "S-001"), rows.stream().map(r -> r.get("studentNumber")).toList(), "lowest rate first");
        assertEquals(Map.of("present", 0, "late", 0, "absent", 1, "excused", 1, "ratePercent", 0), counts(rows.get(0)),
                "excused classes are left out of the rate");
        assertEquals(50, counts(rows.get(1)).get("ratePercent"), "late counts as attended");
        assertEquals(100, counts(rows.get(2)).get("ratePercent"));
        Map<String, Object> totals = asMap(summary.get("totals"));
        assertEquals(2, totals.get("present"));
        assertEquals(60, totals.get("ratePercent"), "3 attended of 5 counted");

        Map<String, Object> april = HttpApi.parse(api.get("/api/v1/batches/" + batchA + "/attendance/summary?from=2026-04-01&to=2026-04-30", admin));
        assertEquals(1, april.get("finalClasses"), "a class's date is its date in its own time zone");

        Map<String, Object> b = HttpApi.parse(api.get("/api/v1/batches/" + batchB + "/attendance/summary?from=2026-03-01&to=2026-03-31", admin));
        assertEquals(List.of("S-003", "S-004"), ((List<Map<String, Object>>) b.get("students")).stream().map(r -> r.get("studentNumber"))
                .sorted().toList(), "a student in two batches counts in each");

        assertEquals(400, api.get("/api/v1/batches/" + batchA + "/attendance/summary?from=2026-03-31&to=2026-03-01", admin).statusCode());
        assertEquals(400, api.get("/api/v1/batches/" + batchA + "/attendance/summary?from=2026-01-01&to=2027-06-01", admin).statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAcademyReportGivesTotalsPerBatchWithoutNames() {
        String analyst = tokenFor(academyId, Role.ANALYST);
        HttpResponse<String> response = api.get("/api/v1/attendance/summary?from=2026-03-01&to=2026-03-31", analyst);
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("S-00"), "no student numbers");
        assertFalse(response.body().contains("@example.test"), "no emails");
        List<Map<String, Object>> batches = (List<Map<String, Object>>) HttpApi.parse(response).get("batches");
        assertEquals(List.of("A", "B"), batches.stream().map(r -> r.get("batchCode")).toList());
        assertEquals(Map.of("present", 2, "late", 1, "absent", 2, "excused", 1, "ratePercent", 60), asMap(batches.get(0).get("totals")));
        assertEquals(1, batches.get(0).get("classesNotFinal"));
        assertEquals(Map.of("present", 1, "late", 1, "absent", 2, "excused", 0, "ratePercent", 50), asMap(batches.get(1).get("totals")));
        assertEquals(200, api.get("/api/v1/attendance/summary?from=2026-03-01&to=2026-03-31", admin).statusCode());
        assertEquals(204, api.get("/api/v1/attendance-policy", analyst).statusCode(), "analysts read the rule (none recorded yet)");

        Map<String, Object> empty = HttpApi.parse(api.get("/api/v1/attendance/summary?from=2025-01-01&to=2025-01-31", admin));
        assertEquals(List.of(), empty.get("batches"), "batches without classes in the range are left out");
    }

    @Test
    void eachRoleSeesOnlyWhatItMay() {
        accountIdOf("teacher.a@example.invalid", academyId, Role.INSTRUCTOR);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("email", "teacher.a@example.invalid");
        fields.put("role", "INSTRUCTOR");
        assertEquals(201, api.post("/api/v1/batches/" + batchA + "/assignments", HttpApi.json(fields), admin).statusCode());
        String teacher = login("teacher.a@example.invalid");
        String range = "/attendance/summary?from=2026-03-01&to=2026-03-31";
        assertEquals(200, api.get("/api/v1/batches/" + batchA + range, teacher).statusCode());
        assertEquals(404, api.get("/api/v1/batches/" + batchB + range, teacher).statusCode(), "not their batch");
        assertEquals(403, api.get("/api/v1/attendance/summary?from=2026-03-01&to=2026-03-31", teacher).statusCode(), "no academy totals");

        String analyst = tokenFor(academyId, Role.ANALYST);
        assertEquals(403, api.get("/api/v1/batches/" + batchA + range, analyst).statusCode(), "analysts never see named rows");
        String student = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, api.get("/api/v1/batches/" + batchA + range, student).statusCode());
        assertEquals(403, api.get("/api/v1/attendance/summary?from=2026-03-01&to=2026-03-31", student).statusCode());

        UUID other = academy("Another academy");
        String outsider = tokenFor(other, Role.ACADEMY_ADMIN);
        assertEquals(404, api.get("/api/v1/batches/" + batchA + range, outsider).statusCode(), "another academy's batch does not exist");
        assertEquals(List.of(), HttpApi.parse(api.get("/api/v1/attendance/summary?from=2026-03-01&to=2026-03-31", outsider)).get("batches"));
    }
}
