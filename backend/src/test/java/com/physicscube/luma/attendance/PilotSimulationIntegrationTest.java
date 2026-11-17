package com.physicscube.luma.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import com.physicscube.luma.zoom.ZoomReceiptProcessor;
import com.physicscube.luma.zoom.ZoomTestReceipts;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Pilot Phase 2 simulated (docs/delivery/PILOT-PLAN.md): one batch for two weeks, six classes, compared class by class
 * with the register a teacher would keep. Everything runs through the real API and workers; only Zoom's side (live
 * events and the participant report) is stood in for, shaped like the 13 Nov 2026 real webinar.
 *
 * <p>The register is written by hand from what happened in each class (who joined when), never computed with the
 * application's rule, so a wrong rule, a lost rejoin, a doubled student or a wrong match shows up as a difference.
 */
class PilotSimulationIntegrationTest extends StaffApiIntegrationTest {
    private static final String WEBINAR = "84400011122";
    private static final String[] CLASS_DATES = {"2026-03-02", "2026-03-04", "2026-03-06", "2026-03-09", "2026-03-11", "2026-03-13"};

    /** What a student did in one class, the Zoom join sessions it produces, and the register's verdict (rule: 75 %, 10 min). */
    enum Visit {
        FULL("PRESENT", "13:00-15:00"),
        EARLY_JOIN("PRESENT", "12:50-15:00"),           // time before the class does not count, but nothing is lost
        IN_GRACE("PRESENT", "13:10-15:00"),             // 10 minutes late is still on time
        LATE("LATE", "13:15-15:00"),
        SHORT("ABSENT", "13:00-13:30"),
        NONE("ABSENT"),
        REJOIN("PRESENT", "13:02-13:50", "13:40-13:55", "14:00-15:00"), // two devices overlap; 113 minutes in all
        BORDER("PRESENT", "13:00-14:30"),               // exactly 75 %
        JUST_SHORT("ABSENT", "13:00-14:29"),
        LATE_AND_SHORT("ABSENT", "13:30-14:30");

        final String register;
        final String[] sessions;

        Visit(String register, String... sessions) {
            this.register = register;
            this.sessions = sessions;
        }
    }

    @Autowired
    ZoomReceiptProcessor processor;
    @Autowired
    ApplicationContext context;

    UUID academyId;
    String owner;
    String admin;

    @Test
    @SuppressWarnings("unchecked")
    void twoWeeksOfOneBatchMatchTheTeachersRegisterExactly() {
        academyId = academy("Physics Cube Pilot");
        owner = tokenFor(academyId, Role.ACADEMY_OWNER);
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        AcademyFixtures fixtures = new AcademyFixtures(api, admin);
        String course = fixtures.course("PHY-PILOT");
        String batchA = fixtures.batch(course, "A");
        String batchB = fixtures.batch(course, "B");

        // 30 students in batch A; S-101 is also in B (one record per class, not two); S-129 joins A in week 2;
        // S-131 and S-132 are only in B, which shares the classes.
        Map<String, String> ids = new TreeMap<>();
        for (int n = 101; n <= 132; n++) {
            String number = "S-" + n;
            ids.put(number, (String) fixtures.student(number, "s" + n + "@pilot.test").get("id"));
            String batch = n >= 131 ? batchB : batchA;
            assertEquals(201, fixtures.enroll(batch, ids.get(number), n == 129 ? "2026-03-09" : "2026-02-01").statusCode());
        }
        assertEquals(201, fixtures.enroll(batchB, ids.get("S-101"), "2026-02-01").statusCode());
        HttpResponse<String> rule = api.put("/api/v1/attendance-policy",
                "{\"minConnectedPercent\":75,\"lateAfterMinutes\":10,\"description\":\"Pilot rule\",\"version\":null}", owner);
        assertEquals(200, rule.statusCode(), rule.body());

        Map<String, String> register = new TreeMap<>();
        Map<String, String> actual = new TreeMap<>();
        Map<String, Integer> totals = new TreeMap<>();
        for (int c = 0; c < CLASS_DATES.length; c++) {
            String date = CLASS_DATES[c];
            String occurrence = "Pilot" + c + "==";
            HttpResponse<String> created = api.post("/api/v1/class-sessions", "{\"title\":\"Mechanics " + (c + 1) + "\",\"startsAt\":\""
                    + date + "T13:00:00Z\",\"endsAt\":\"" + date + "T15:00:00Z\",\"batchIds\":[\"" + batchA + "\",\"" + batchB
                    + "\"],\"zoomWebinarId\":\"" + WEBINAR + "\"}", admin);
            assertEquals(201, created.statusCode(), created.body());
            String session = (String) HttpApi.parse(created).get("id");

            // Zoom: the webinar starts (the run links itself to the class), then the report arrives after the end.
            long ts = Instant.parse(date + "T12:58:00Z").toEpochMilli();
            ZoomTestReceipts.store(context, academyId, "webinar.started", ts, ("{\"event\":\"webinar.started\",\"event_ts\":" + ts
                    + ",\"payload\":{\"object\":{\"id\":\"" + WEBINAR + "\",\"uuid\":\"" + occurrence + "\",\"host_id\":\"hostuser\"}}}")
                    .getBytes(StandardCharsets.UTF_8));
            processor.runOnce();
            jdbc.update("INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, webinar_id, status, attempts, fetched_at, join_sessions) "
                    + "VALUES (?, ?, ?, 'FETCHED', 1, now(), 0)", academyId, occurrence, WEBINAR);
            row(occurrence, date, "host@academy.test", null, true, "12:45-15:05");
            row(occurrence, date, "guest." + c + "@zoom.test", null, false, "13:00-15:00"); // not a student: stays for review

            for (int n = 101; n <= 132; n++) {
                String number = "S-" + n;
                if (n == 129 && c < 3) {
                    continue; // not yet in the batch: not expected, not in the register
                }
                Visit visit = Visit.values()[(n * 3 + c * 7) % Visit.values().length];
                register.put(date + " " + number, visit.register);
                String email = "s" + n + "@pilot.test";
                String registered = null;
                if (n == 127) { // registered with the academy email, joined signed in to another Zoom account
                    registered = email;
                    email = "other.127@zoom.test";
                } else if (n == 128) {
                    email = "family.128@zoom.test";
                }
                for (String span : visit.sessions) {
                    row(occurrence, date, email, registered, false, span);
                }
            }

            if (c == 0) {
                // Zoom review after the first class, seen only in Zoom's report (no live events this time): the family
                // account and a guest wait for a decision; S-127's other account is matched by its registration email.
                // Staff decide the family account once; it holds for every later class.
                List<String> waiting = new ArrayList<>();
                for (Object p : HttpApi.parseList(api.get("/api/v1/zoom/participants/unmatched", admin))) {
                    waiting.add((String) ((Map<String, Object>) p).get("email"));
                }
                assertEquals(List.of("family.128@zoom.test", "guest.0@zoom.test"), waiting.stream().sorted().toList());
                HttpResponse<String> decided = api.post("/api/v1/zoom/identity-links", "{\"email\":\"family.128@zoom.test\",\"studentId\":\""
                        + ids.get("S-128") + "\",\"reason\":\"Parent confirmed by phone\"}", admin);
                assertEquals(200, decided.statusCode(), decided.body());
            }
            HttpResponse<String> finalised = api.post("/api/v1/class-sessions/" + session + "/attendance/finalisation", "{}", admin);
            assertEquals(200, finalised.statusCode(), finalised.body());
            for (Map<String, Object> s : (List<Map<String, Object>>) HttpApi.parse(
                    api.get("/api/v1/class-sessions/" + session + "/attendance", admin)).get("students")) {
                if (s.get("outcome") != null) {
                    actual.put(date + " " + s.get("studentNumber"), (String) s.get("outcome"));
                    totals.merge((String) s.get("outcome"), 1, Integer::sum);
                }
            }
        }

        List<String> differences = new ArrayList<>();
        register.forEach((key, expected) -> {
            if (!expected.equals(actual.get(key))) {
                differences.add(key + ": register " + expected + ", LUMA " + actual.get(key));
            }
        });
        actual.keySet().stream().filter(k -> !register.containsKey(k)).forEach(k -> differences.add(k + ": not in the register"));
        System.out.println("Pilot simulation: " + CLASS_DATES.length + " classes, " + register.size() + " register entries, "
                + actual.size() + " final records " + totals + ", differences " + differences.size());
        assertEquals(List.of(), differences);
        assertEquals(6 * 32 - 3, register.size());
        assertTrue(totals.keySet().containsAll(List.of("PRESENT", "LATE", "ABSENT")), totals.toString());

        // The batch CSV for the fortnight carries the same outcomes (batch A: everyone but S-131 and S-132).
        String csv = api.get("/api/v1/batches/" + batchA + "/attendance/export?from=2026-03-01&to=2026-03-14", admin).body();
        long rows = csv.lines().skip(1).count();
        assertEquals(6 * 30 - 3, rows, "one row per student and class in batch A");
        for (String line : csv.lines().skip(1).toList()) {
            String[] cells = line.split(",");
            String key = cells[0] + " " + cells[7];
            assertEquals(register.get(key), cells[cells.length - 1], line);
        }
        // Every Zoom account that is not a student is still waiting for a decision; the host and matched students never are.
        List<String> unmatched = new ArrayList<>();
        for (Object p : HttpApi.parseList(api.get("/api/v1/zoom/participants/unmatched", admin))) {
            unmatched.add((String) ((Map<String, Object>) p).get("email"));
        }
        assertEquals(List.of("guest.0@zoom.test", "guest.1@zoom.test", "guest.2@zoom.test", "guest.3@zoom.test", "guest.4@zoom.test",
                "guest.5@zoom.test"), unmatched.stream().sorted().toList());
    }

    private void row(String occurrence, String date, String email, String registeredEmail, boolean host, String span) {
        String[] times = span.split("-");
        jdbc.update("INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, display_name, joined_at, left_at, "
                + "duration_seconds, host, registrant_email) VALUES (?, ?, ?, ?, 'Someone', ?, ?, 0, ?, ?)", UUID.randomUUID(), academyId,
                occurrence, email, Timestamp.from(Instant.parse(date + "T" + times[0] + ":00Z")),
                Timestamp.from(Instant.parse(date + "T" + times[1] + ":00Z")), host, registeredEmail);
    }

}
