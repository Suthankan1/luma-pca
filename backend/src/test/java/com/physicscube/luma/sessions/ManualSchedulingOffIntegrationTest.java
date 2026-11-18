package com.physicscube.luma.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Academy decision 2026-11-18: with the default settings LUMA cannot schedule, edit, cancel or import classes. */
@TestPropertySource(properties = "luma.sessions.manual-scheduling=false")
class ManualSchedulingOffIntegrationTest extends StaffApiIntegrationTest {
    private int create(String role, UUID academyId, String batch) {
        return api.post("/api/v1/class-sessions", "{\"title\":\"Waves\",\"startsAt\":\"2027-01-06T12:30:00Z\","
                + "\"endsAt\":\"2027-01-06T14:30:00Z\",\"batchIds\":[\"" + batch + "\"]}", tokenFor(academyId, Role.valueOf(role))).statusCode();
    }

    @Test
    void everySchedulingRouteIsRefusedAndHistoryStaysReadable() {
        UUID academyId = academy("Physics Cube Test");
        String admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        String batch = new AcademyFixtures(api, admin).batch(new AcademyFixtures(api, admin).course("PHY-2027"), "MON");
        // A class from before the change (inserted directly): still readable.
        UUID old = UUID.randomUUID();
        jdbc.update("INSERT INTO class_sessions (id, academy_id, title, starts_at, ends_at, time_zone) VALUES (?, ?, 'Old class', "
                + "now() - interval '3 days', now() - interval '3 days' + interval '2 hours', 'Asia/Colombo')", old, academyId);
        jdbc.update("INSERT INTO session_batches (session_id, batch_id, academy_id) VALUES (?, ?::uuid, ?)", old, batch, academyId);

        HttpResponse<String> create = api.post("/api/v1/class-sessions", "{\"title\":\"Waves\",\"startsAt\":\"2027-01-06T12:30:00Z\","
                + "\"endsAt\":\"2027-01-06T14:30:00Z\",\"batchIds\":[\"" + batch + "\"]}", admin);
        assertEquals(409, create.statusCode());
        assertTrue(HttpApi.parse(create).get("detail").toString().contains("Zoom owns webinar schedules"));
        assertEquals(409, api.post("/api/v1/class-sessions/series", "{\"title\":\"Weekly\",\"firstDate\":\"2027-01-06\",\"lastDate\":\"2027-02-06\","
                + "\"weekdays\":[\"WEDNESDAY\"],\"startTime\":\"18:00\",\"endTime\":\"20:00\",\"batchIds\":[\"" + batch + "\"]}", admin).statusCode());
        assertEquals(409, api.put("/api/v1/class-sessions/" + old, "{\"title\":\"Moved\",\"startsAt\":\"2027-01-06T12:30:00Z\","
                + "\"endsAt\":\"2027-01-06T14:30:00Z\",\"batchIds\":[\"" + batch + "\"],\"status\":\"CANCELLED\",\"version\":0}", admin).statusCode());
        assertEquals(409, api.post("/api/v1/zoom/webinar-imports", "{\"webinarId\":\"84400000101\",\"batchIds\":[\"" + batch + "\"]}", admin)
                .statusCode());
        assertEquals(403, create("INSTRUCTOR", academyId, batch), "roles are still checked first");

        assertEquals(200, api.get("/api/v1/class-sessions/" + old, admin).statusCode(), "history stays readable");
        assertEquals("Old class", jdbc.queryForObject("SELECT title FROM class_sessions WHERE id = ?", String.class, old));
    }
}
