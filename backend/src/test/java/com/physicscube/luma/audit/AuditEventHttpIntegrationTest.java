package com.physicscube.luma.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GET /api/v1/audit-events: the academy's activity log, newest first, for owners and administrators. */
class AuditEventHttpIntegrationTest extends StaffApiIntegrationTest {
    UUID academyId;
    String owner;
    String teacherId;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        accountIdOf("owner@example.invalid", academyId, Role.STAFF);
        owner = login("owner@example.invalid");
        HttpResponse<String> created = api.post("/api/v1/staff-accounts",
                "{\"email\":\"teacher@example.invalid\",\"roles\":[\"INSTRUCTOR\"]}", owner);
        assertEquals(201, created.statusCode(), created.body());
        teacherId = (String) HttpApi.parse(created).get("id");
        assertEquals(201, api.post("/api/v1/accounts/" + teacherId + "/invitations", "", owner).statusCode());
        assertEquals(200, api.post("/api/v1/staff-accounts/" + teacherId + "/disable", "", owner).statusCode());
        AcademyFixtures fixtures = new AcademyFixtures(api, owner);
        String batch = fixtures.batch(fixtures.course("PHY-2027"), "MON");
        assertEquals(200, api.post("/api/v1/students/import", "{\"rows\":[{\"studentNumber\":\"PCA-1\",\"fullName\":\"Ann\","
                + "\"email\":\"ann@example.invalid\"}],\"batchId\":\"" + batch + "\",\"enrolledOn\":\"2027-01-10\"}", owner).statusCode());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String query, String token) {
        HttpResponse<String> response = api.get("/api/v1/audit-events" + query, token);
        assertEquals(200, response.statusCode(), response.body());
        return (List<Map<String, Object>>) HttpApi.parse(response).get("items");
    }

    private static List<Object> actions(List<Map<String, Object>> items) {
        return items.stream().map(i -> i.get("action")).toList();
    }

    @Test
    void ownersSeeTheirAcademysActivityNewestFirstWithReadableNames() {
        List<Map<String, Object>> all = items("", owner);

        assertEquals(List.of("STUDENTS_IMPORTED", "STAFF_ACCOUNT_DISABLED", "INVITATION_ISSUED", "STAFF_ACCOUNT_CREATED"), actions(all));
        Map<String, Object> disabled = all.get(1);
        assertEquals("owner@example.invalid", disabled.get("actorEmail"));
        assertEquals("ACCOUNT", disabled.get("targetType"));
        assertEquals(teacherId, disabled.get("targetId"));
        assertEquals("teacher@example.invalid", disabled.get("targetLabel"));
        Map<String, Object> imported = all.get(0);
        assertEquals("BATCH", imported.get("targetType"));
        assertEquals("MON", imported.get("targetLabel"));
        assertEquals(Map.of("students", "1", "enrolled", "1", "enrolledOn", "2027-01-10"), imported.get("details"));
        assertTrue(Map.class.isInstance(all.get(2).get("details")));
        assertEquals("LINK", ((Map<?, ?>) all.get(2).get("details")).get("delivery"));
    }

    @Test
    void activityIsFilteredByAreaOrByOneRecord() {
        assertEquals(List.of("STAFF_ACCOUNT_DISABLED", "STAFF_ACCOUNT_CREATED"), actions(items("?category=staff", owner)));
        assertEquals(List.of("INVITATION_ISSUED"), actions(items("?category=sign-in", owner)));
        assertEquals(List.of("STUDENTS_IMPORTED"), actions(items("?category=students", owner)));
        assertEquals(List.of(), actions(items("?category=zoom", owner)));
        assertEquals(List.of("STAFF_ACCOUNT_DISABLED", "INVITATION_ISSUED", "STAFF_ACCOUNT_CREATED"),
                actions(items("?targetId=" + teacherId, owner)), "one staff member's history");
        assertEquals(400, api.get("/api/v1/audit-events?category=everything", owner).statusCode());
    }

    @Test
    void pagesFollowEachOtherWithoutGapsOrRepeats() {
        // Same-instant events still page correctly: the id breaks ties.
        jdbc.update("INSERT INTO audit_events (id, academy_id, action, target_type, details, occurred_at) "
                + "SELECT gen_random_uuid(), ?, 'STAFF_TEST_EVENT', 'ACADEMY', '{}'::jsonb, '2026-01-01T00:00:00Z' FROM generate_series(1, 5)",
                academyId);
        List<Object> ids = new ArrayList<>();
        String before = null;
        int pages = 0;
        do {
            HttpResponse<String> response = api.get("/api/v1/audit-events?limit=3" + (before == null ? "" : "&before=" + before), owner);
            assertEquals(200, response.statusCode(), response.body());
            Map<String, Object> page = HttpApi.parse(response);
            ((List<?>) page.get("items")).forEach(item -> ids.add(((Map<?, ?>) item).get("id")));
            before = (String) page.get("nextBefore");
            pages++;
        } while (before != null && pages < 10);
        assertEquals(9, ids.size());
        assertEquals(9, new HashSet<>(ids).size());
        assertEquals(3, pages);
        assertNull(HttpApi.parse(api.get("/api/v1/audit-events?limit=200", owner)).get("nextBefore"));
    }

    @Test
    void onlyStaffOfTheAcademySeeIt() {
        assertEquals(4, items("", tokenFor(academyId, Role.STAFF)).size());
        assertEquals(403, api.get("/api/v1/audit-events", tokenFor(academyId, Role.STUDENT)).statusCode());
        assertEquals(401, api.get("/api/v1/audit-events", null).statusCode());
        assertEquals(List.of(), items("", tokenFor(academy("Other Academy"), Role.STAFF)), "another academy sees none of it");
        for (String bad : List.of("?limit=0", "?limit=201", "?before=yesterday", "?before=1_not-a-uuid", "?targetId=nope")) {
            assertEquals(400, api.get("/api/v1/audit-events" + bad, owner).statusCode(), bad);
        }
    }
}
