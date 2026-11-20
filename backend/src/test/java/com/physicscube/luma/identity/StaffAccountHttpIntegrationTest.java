package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** Staff administration with one STAFF role (owner decision 2026-11-20): any active staff member manages staff. */
class StaffAccountHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final AtomicInteger PEOPLE = new AtomicInteger();

    UUID academyId;
    String first;
    UUID firstId;
    String second;
    UUID secondId;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        String firstEmail = email("first");
        firstId = accountIdOf(firstEmail, academyId, Role.STAFF);
        first = login(firstEmail);
        String secondEmail = email("second");
        secondId = accountIdOf(secondEmail, academyId, Role.STAFF);
        second = login(secondEmail);
    }

    private static String email(String prefix) {
        return prefix + PEOPLE.incrementAndGet() + "@example.invalid";
    }

    private HttpResponse<String> createStaff(String token, String email) {
        return api.post("/api/v1/staff-accounts", HttpApi.json(Map.of("email", email)), token);
    }

    private Map<String, Object> created(String token, String email) {
        HttpResponse<String> response = createStaff(token, email);
        assertEquals(201, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private HttpResponse<String> disable(String token, Object accountId) {
        return api.post("/api/v1/staff-accounts/" + accountId + "/disable", "", token);
    }

    private HttpResponse<String> enable(String token, Object accountId, String body) {
        return api.post("/api/v1/staff-accounts/" + accountId + "/enable", body, token);
    }

    private HttpResponse<String> invite(String token, Object accountId) {
        return api.post("/api/v1/accounts/" + accountId + "/invitations", "", token);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
    }

    private int activeStaff() {
        return jdbc.queryForObject("SELECT count(*) FROM accounts a JOIN account_roles r ON r.account_id = a.id "
                + "WHERE a.academy_id = ? AND r.role = 'STAFF' AND a.status = 'ACTIVE'", Integer.class, academyId);
    }

    /** Creates a pending staff account, invites and activates it, and returns its email. */
    private String activeStaff(String creator) {
        String address = email("staff");
        Object id = created(creator, address).get("id");
        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(creator, id)).get("activationToken")));
        return address;
    }

    @Test
    void anyStaffMemberCreatesStaffWhoAlwaysGetTheOneStaffRole() {
        Map<String, Object> staff = created(second, " " + email("new").toUpperCase() + " ");
        assertEquals(List.of("STAFF"), staff.get("roles"));
        assertEquals("PENDING_ACTIVATION", staff.get("status"));

        // A client still sending a former role list cannot choose another role: the field is ignored.
        HttpResponse<String> legacy = api.post("/api/v1/staff-accounts",
                "{\"email\":\"" + email("legacy") + "\",\"roles\":[\"ACADEMY_OWNER\",\"STUDENT\"]}", first);
        assertEquals(201, legacy.statusCode(), legacy.body());
        assertEquals(List.of("STAFF"), HttpApi.parse(legacy).get("roles"));
        assertEquals(List.of("STAFF"), jdbc.queryForList("SELECT role FROM account_roles WHERE account_id = ?::uuid", String.class,
                HttpApi.parse(legacy).get("id")));
    }

    @Test
    void invalidStaffRequestsAreRejected() {
        String taken = email("taken");
        created(first, taken);
        assertEquals(400, createStaff(first, "not-an-email").statusCode());
        assertEquals(409, createStaff(first, taken.toUpperCase()).statusCode());
        assertEquals(400, api.post("/api/v1/staff-accounts", "{}", first).statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStaffListShowsStaffButNeverStudents() {
        Object added = created(second, email("teacher")).get("id");
        String studentId = (String) new AcademyFixtures(api, first).student("PCA-001", "learner@example.invalid").get("accountId");
        accountIdOf(email("elsewhere"), academy("Other Academy"), Role.STAFF);

        List<Map<String, Object>> listed = HttpApi.parseList(api.get("/api/v1/staff-accounts", second)).stream()
                .map(item -> (Map<String, Object>) item).toList();

        assertEquals(3, listed.size(), "the two seeded staff and the new one, of this academy only");
        assertTrue(listed.stream().anyMatch(a -> a.get("id").equals(added)));
        assertTrue(listed.stream().allMatch(a -> List.of("STAFF").equals(a.get("roles"))));
        assertTrue(listed.stream().noneMatch(a -> a.get("id").equals(studentId)));
        assertEquals(404, api.get("/api/v1/staff-accounts/" + studentId, first).statusCode());
        assertEquals(404, api.put("/api/v1/staff-accounts/" + added + "/roles", "{\"roles\":[\"STAFF\"],\"version\":0}", first)
                .statusCode(), "there are no roles to change any more");
    }

    @Test
    void anInvitationActivatesAStaffAccountEndToEnd() {
        String address = email("teacher");
        Object id = created(first, address).get("id");
        assertEquals(401, passwordLogin(address).statusCode(), "pending accounts cannot sign in");

        HttpResponse<String> issued = invite(second, id);

        assertEquals(201, issued.statusCode(), issued.body());
        assertTrue(issued.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        String token = (String) HttpApi.parse(issued).get("activationToken");
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals("/activate#token=" + token, HttpApi.parse(issued).get("activationPath"));
        assertEquals(204, activateWithInvitation(token));
        HttpResponse<String> signedIn = passwordLogin(address);
        assertEquals(200, signedIn.statusCode());
        assertEquals(List.of("STAFF"), HttpApi.parse(api.get("/api/v1/auth/me", (String) HttpApi.parse(signedIn).get("accessToken")))
                .get("roles"));
        assertEquals("ACTIVE", HttpApi.parse(api.get("/api/v1/staff-accounts/" + id, first)).get("status"));
        assertEquals(409, invite(first, id).statusCode(), "an active account needs no invitation");
        assertEquals(400, activateWithInvitation(token), "single use");
    }

    @Test
    void reissuingAnInvitationRevokesTheEarlierOne() {
        Object id = created(first, email("teacher")).get("id");
        String earlier = (String) HttpApi.parse(invite(first, id)).get("activationToken");
        String later = (String) HttpApi.parse(invite(second, id)).get("activationToken");

        assertNotEquals(earlier, later);
        assertEquals(400, activateWithInvitation(earlier));
        assertEquals(204, activateWithInvitation(later));
    }

    @Test
    void staffInviteStudentsWhoCanThenSignIn() {
        Map<String, Object> student = new AcademyFixtures(api, first).student("PCA-001", "learner@example.invalid");

        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(second, student.get("accountId"))).get("activationToken")));

        String token = (String) HttpApi.parse(passwordLogin("learner@example.invalid")).get("accessToken");
        assertEquals(student.get("id"), HttpApi.parse(api.get("/api/v1/me/student", token)).get("id"));
    }

    @Test
    void anyStaffMemberDisablesAndReEnablesAnotherStaffMember() {
        assertEquals(200, disable(second, firstId).statusCode(), "there is no protected owner any more");
        assertEquals(401, api.get("/api/v1/auth/me", first).statusCode());
        assertEquals(200, enable(second, firstId, "").statusCode());
        assertEquals(200, passwordLogin(accounts.findById(firstId).orElseThrow().email()).statusCode());
    }

    @Test
    void nobodyDisablesThemselves() {
        assertEquals(403, disable(first, firstId).statusCode());
        assertEquals(403, disable(second, secondId).statusCode());
        assertEquals(2, activeStaff());
        assertEquals(0, auditCount("STAFF_ACCOUNT_DISABLED"), "refused actions leave no audit record");
    }

    @Test
    void disablingEndsEverySessionImmediately() {
        String address = activeStaff(first);
        Map<String, Object> session = loginSession(address);
        String access = (String) session.get("accessToken");
        assertEquals(200, api.get("/api/v1/auth/me", access).statusCode());
        Object id = accounts.findByEmail(address).orElseThrow().id();

        HttpResponse<String> response = disable(second, id);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("DISABLED", HttpApi.parse(response).get("status"));
        assertEquals(401, api.get("/api/v1/auth/me", access).statusCode());
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", (String) session.get("refreshToken"))))
                .statusCode());
        assertEquals(401, passwordLogin(address).statusCode());
        assertEquals(200, disable(first, id).statusCode(), "disabling again is harmless");
        assertEquals(1, auditCount("STAFF_ACCOUNT_DISABLED"));
    }

    @Test
    void twoStaffDisablingEachOtherAtOnceLeaveOneActive() throws Exception {
        List<Integer> statuses = race(List.of(() -> disable(first, secondId).statusCode(),
                () -> disable(second, firstId).statusCode()));

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(s -> s == 403).count(), "the loser is no longer active staff: " + statuses);
        assertEquals(1, activeStaff(), "the academy keeps one active staff account");
    }

    private List<Integer> race(List<Callable<Integer>> attempts) throws Exception {
        CyclicBarrier start = new CyclicBarrier(attempts.size());
        ExecutorService pool = Executors.newFixedThreadPool(attempts.size());
        try {
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(attempts.stream().<Callable<Integer>>map(attempt -> () -> {
                start.await();
                return attempt.call();
            }).toList())) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void otherAcademiesAndStudentsCannotAdministerStaff() {
        Object added = created(first, email("teacher")).get("id");
        String outsider = tokenFor(academy("Other Academy"), Role.STAFF);
        assertEquals(404, api.get("/api/v1/staff-accounts/" + added, outsider).statusCode());
        assertEquals(404, disable(outsider, added).statusCode());
        assertEquals(404, enable(outsider, added, "").statusCode());
        assertEquals(404, invite(outsider, added).statusCode());
        assertEquals(1, HttpApi.parseList(api.get("/api/v1/staff-accounts", outsider)).size(),
                "the outsider sees only their own academy's staff");

        String student = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, api.get("/api/v1/staff-accounts", student).statusCode());
        assertEquals(403, createStaff(student, email("x")).statusCode());
        assertEquals(403, disable(student, added).statusCode());
        assertEquals(403, enable(student, added, "").statusCode());
        assertEquals(403, invite(student, added).statusCode());
        assertEquals(401, api.get("/api/v1/staff-accounts", null).statusCode());
        assertEquals(401, invite(null, added).statusCode());
        assertEquals("PENDING_ACTIVATION", HttpApi.parse(api.get("/api/v1/staff-accounts/" + added, first)).get("status"));
    }

    @Test
    void sensitiveActionsAreAuditedWithoutPersonalDataOrTokens() {
        String address = email("teacher");
        Object id = created(second, address).get("id");
        String token = (String) HttpApi.parse(invite(second, id)).get("activationToken");
        disable(second, id);

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT actor_account_id, action, target_type, target_id, details::text AS details FROM audit_events ORDER BY occurred_at");

        assertEquals(List.of("STAFF_ACCOUNT_CREATED", "INVITATION_ISSUED", "STAFF_ACCOUNT_DISABLED"),
                events.stream().map(e -> e.get("action")).toList());
        for (Map<String, Object> event : events) {
            assertEquals(secondId, event.get("actor_account_id"));
            assertEquals("ACCOUNT", event.get("target_type"));
            assertEquals(id, event.get("target_id").toString());
            assertFalse(event.get("details").toString().contains(address), "no email in audit details");
            assertFalse(event.get("details").toString().contains(token), "no token in audit details");
        }
        assertEquals("{\"roles\": [\"STAFF\"]}", events.get(0).get("details").toString());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE audit_events SET action = 'TAMPERED'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM audit_events"));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    @Test
    void aReEnabledAccountSignsInWithItsPasswordButOldSessionsStayEnded() {
        String address = activeStaff(first);
        Map<String, Object> before = loginSession(address);
        Object id = accounts.findByEmail(address).orElseThrow().id();
        assertEquals(200, disable(first, id).statusCode());

        HttpResponse<String> response = enable(second, id, "");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("ACTIVE", HttpApi.parse(response).get("status"));
        assertEquals(401, api.get("/api/v1/auth/me", (String) before.get("accessToken")).statusCode(), "old tokens stay invalid");
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", (String) before.get("refreshToken"))))
                .statusCode(), "old sessions stay ended");
        Map<String, Object> again = loginSession(address);
        assertEquals(200, api.get("/api/v1/auth/me", (String) again.get("accessToken")).statusCode(), "signs in with the same password");

        assertEquals(200, enable(first, id, "").statusCode(), "enabling an active account changes nothing");
        assertEquals(1, auditCount("STAFF_ACCOUNT_ENABLED"));
        assertEquals("{\"statusNow\": \"ACTIVE\", \"newPasswordRequired\": \"false\"}", jdbc.queryForObject(
                "SELECT details::text FROM audit_events WHERE action = 'STAFF_ACCOUNT_ENABLED'", String.class));
    }

    @Test
    void reEnablingCanRequireANewPasswordFromAFreshInvitation() {
        String address = activeStaff(first);
        Object id = accounts.findByEmail(address).orElseThrow().id();
        assertEquals(200, disable(first, id).statusCode());

        HttpResponse<String> response = enable(first, id, "{\"requireNewPassword\":true}");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("PENDING_ACTIVATION", HttpApi.parse(response).get("status"));
        assertEquals(401, passwordLogin(address).statusCode(), "the old password no longer works");
        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(first, id)).get("activationToken")));
        assertEquals(200, passwordLogin(address).statusCode());
    }

    @Test
    void anAccountDisabledBeforeActivationReturnsToPending() {
        Object id = created(first, email("pending")).get("id");
        String token = (String) HttpApi.parse(invite(first, id)).get("activationToken");
        assertEquals(200, disable(first, id).statusCode());

        assertEquals("PENDING_ACTIVATION", HttpApi.parse(enable(first, id, "")).get("status"));
        assertEquals(400, activateWithInvitation(token), "the invitation issued before the disable no longer works");
        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(first, id)).get("activationToken")));
    }

    @Test
    void studentsAndUnknownAccountsAreNotStaffAccounts() {
        Map<String, Object> studentRecord = new AcademyFixtures(api, first).student("PCA-1", "student.enable@example.invalid");
        assertEquals(404, enable(first, studentRecord.get("accountId"), "").statusCode(), "students are not staff accounts");
        assertEquals(404, disable(first, studentRecord.get("accountId")).statusCode());
        assertEquals(404, enable(first, UUID.randomUUID(), "").statusCode());
    }
}
