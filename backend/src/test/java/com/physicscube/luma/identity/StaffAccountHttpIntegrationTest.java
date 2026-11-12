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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class StaffAccountHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final AtomicInteger PEOPLE = new AtomicInteger();

    UUID academyId;
    String owner;
    UUID ownerId;
    String admin;
    UUID adminId;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        String ownerEmail = email("owner");
        ownerId = accountIdOf(ownerEmail, academyId, Role.ACADEMY_OWNER);
        owner = login(ownerEmail);
        String adminEmail = email("admin");
        adminId = accountIdOf(adminEmail, academyId, Role.ACADEMY_ADMIN);
        admin = login(adminEmail);
    }

    private static String email(String prefix) {
        return prefix + PEOPLE.incrementAndGet() + "@example.invalid";
    }

    private static String roles(String... roles) {
        return Arrays.stream(roles).map(r -> '"' + r + '"').collect(Collectors.joining(",", "[", "]"));
    }

    private HttpResponse<String> createStaff(String token, String email, String... roleNames) {
        return api.post("/api/v1/staff-accounts", "{\"email\":\"" + email + "\",\"roles\":" + roles(roleNames) + "}", token);
    }

    private Map<String, Object> created(String token, String email, String... roleNames) {
        HttpResponse<String> response = createStaff(token, email, roleNames);
        assertEquals(201, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private HttpResponse<String> setRoles(String token, Object accountId, int version, String... roleNames) {
        return api.put("/api/v1/staff-accounts/" + accountId + "/roles",
                "{\"roles\":" + roles(roleNames) + ",\"version\":" + version + "}", token);
    }

    private HttpResponse<String> disable(String token, Object accountId) {
        return api.post("/api/v1/staff-accounts/" + accountId + "/disable", "", token);
    }

    private HttpResponse<String> invite(String token, Object accountId) {
        return api.post("/api/v1/accounts/" + accountId + "/invitations", "", token);
    }

    private int version(Object accountId) {
        return ((Number) HttpApi.parse(api.get("/api/v1/staff-accounts/" + accountId, owner)).get("version")).intValue();
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
    }

    /** Creates a pending staff account, invites and activates it, and returns its email. */
    private String activeStaff(String creator, String... roleNames) {
        String address = email("staff");
        Object id = created(creator, address, roleNames).get("id");
        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(creator, id)).get("activationToken")));
        return address;
    }

    @Test
    void anAdministratorCreatesNonElevatedStaffAndOnlyAnOwnerCreatesAdministrators() {
        for (String role : List.of("INSTRUCTOR", "ATTENDANCE_OFFICER", "ANALYST")) {
            Map<String, Object> staff = created(admin, " " + email(role.toLowerCase()).toUpperCase() + " ", role);
            assertEquals(List.of(role), staff.get("roles"));
            assertEquals("PENDING_ACTIVATION", staff.get("status"));
            assertEquals(false, staff.get("mfaEnrolled"));
        }
        assertEquals(403, createStaff(admin, email("x"), "ACADEMY_ADMIN").statusCode());
        assertEquals(403, createStaff(admin, email("x"), "INSTRUCTOR", "ACADEMY_OWNER").statusCode());
        assertEquals(List.of("ACADEMY_ADMIN"), created(owner, email("second-admin"), "ACADEMY_ADMIN").get("roles"));
        assertEquals(List.of("ACADEMY_OWNER", "INSTRUCTOR"),
                created(owner, email("co-owner"), "INSTRUCTOR", "ACADEMY_OWNER").get("roles"));
    }

    @Test
    void invalidStaffRequestsAreRejected() {
        String taken = email("taken");
        created(admin, taken, "INSTRUCTOR");
        assertEquals(400, createStaff(admin, email("x"), "STUDENT").statusCode(), "students are not staff");
        assertEquals(400, createStaff(admin, email("x")).statusCode(), "no roles");
        assertEquals(400, createStaff(admin, email("x"), "SUPERUSER").statusCode());
        assertEquals(400, createStaff(admin, email("x"), "INSTRUCTOR", "INSTRUCTOR").statusCode());
        assertEquals(400, createStaff(admin, "not-an-email", "INSTRUCTOR").statusCode());
        assertEquals(409, createStaff(admin, taken.toUpperCase(), "ANALYST").statusCode());
        assertEquals(400, api.post("/api/v1/staff-accounts", "{}", admin).statusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStaffListShowsStaffButNeverStudents() {
        Object instructor = created(admin, email("teacher"), "INSTRUCTOR").get("id");
        String studentId = (String) new AcademyFixtures(api, admin).student("PCA-001", "learner@example.invalid").get("accountId");
        accountIdOf(email("elsewhere"), academy("Other Academy"), Role.INSTRUCTOR);

        List<Map<String, Object>> listed = HttpApi.parseList(api.get("/api/v1/staff-accounts", admin)).stream()
                .map(item -> (Map<String, Object>) item).toList();

        assertEquals(3, listed.size(), "owner, admin and instructor of this academy only");
        assertTrue(listed.stream().anyMatch(a -> a.get("id").equals(instructor)));
        assertTrue(listed.stream().noneMatch(a -> a.get("id").equals(studentId)));
        assertEquals(true, listed.stream().filter(a -> a.get("id").equals(adminId.toString())).findFirst().orElseThrow()
                .get("mfaEnrolled"), "administrators signed in with MFA");
        assertEquals(404, api.get("/api/v1/staff-accounts/" + studentId, admin).statusCode());
        assertEquals(404, setRoles(admin, studentId, 0, "INSTRUCTOR").statusCode(), "a student cannot be turned into staff");
    }

    @Test
    void anInvitationActivatesAStaffAccountEndToEnd() {
        String address = email("teacher");
        Object id = created(admin, address, "INSTRUCTOR").get("id");
        assertEquals(401, passwordLogin(address).statusCode(), "pending accounts cannot sign in");

        HttpResponse<String> issued = invite(admin, id);

        assertEquals(201, issued.statusCode(), issued.body());
        assertTrue(issued.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        String token = (String) HttpApi.parse(issued).get("activationToken");
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals("/activate#token=" + token, HttpApi.parse(issued).get("activationPath"));
        assertEquals(204, activateWithInvitation(token));
        assertEquals(200, passwordLogin(address).statusCode());
        assertEquals("ACTIVE", HttpApi.parse(api.get("/api/v1/staff-accounts/" + id, admin)).get("status"));
        assertEquals(409, invite(admin, id).statusCode(), "an active account needs no invitation");
        assertEquals(400, activateWithInvitation(token), "single use");
    }

    @Test
    void reissuingAnInvitationRevokesTheEarlierOne() {
        Object id = created(admin, email("teacher"), "INSTRUCTOR").get("id");
        String first = (String) HttpApi.parse(invite(admin, id)).get("activationToken");
        String second = (String) HttpApi.parse(invite(admin, id)).get("activationToken");

        assertNotEquals(first, second);
        assertEquals(400, activateWithInvitation(first));
        assertEquals(204, activateWithInvitation(second));
    }

    @Test
    void administratorsInviteStudentsWhoCanThenSignIn() {
        Map<String, Object> student = new AcademyFixtures(api, admin).student("PCA-001", "learner@example.invalid");

        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(admin, student.get("accountId"))).get("activationToken")));

        String token = (String) HttpApi.parse(passwordLogin("learner@example.invalid")).get("accessToken");
        assertEquals(student.get("id"), HttpApi.parse(api.get("/api/v1/me/student", token)).get("id"));
    }

    @Test
    void administratorsCannotTouchOwnerOrAdministratorAccounts() {
        Object otherAdmin = created(owner, email("admin-two"), "ACADEMY_ADMIN").get("id");
        Object instructor = created(admin, email("teacher"), "INSTRUCTOR").get("id");

        assertEquals(403, setRoles(admin, instructor, 0, "ACADEMY_ADMIN").statusCode(), "no promotion");
        assertEquals(403, setRoles(admin, otherAdmin, 0, "INSTRUCTOR").statusCode(), "no demotion of an administrator");
        assertEquals(403, setRoles(admin, ownerId, version(ownerId), "INSTRUCTOR").statusCode());
        assertEquals(403, disable(admin, otherAdmin).statusCode());
        assertEquals(403, disable(admin, ownerId).statusCode());
        assertEquals(403, invite(admin, otherAdmin).statusCode(), "only an owner invites an administrator");
        assertEquals(List.of("ACADEMY_ADMIN"), HttpApi.parse(api.get("/api/v1/staff-accounts/" + otherAdmin, owner)).get("roles"));
        assertEquals(0, auditCount("STAFF_ROLES_CHANGED") + auditCount("STAFF_ACCOUNT_DISABLED"),
                "refused actions leave no audit record");

        assertEquals(200, setRoles(owner, instructor, 0, "INSTRUCTOR", "ACADEMY_ADMIN").statusCode());
        assertEquals(201, invite(owner, otherAdmin).statusCode());
        assertEquals(200, disable(owner, otherAdmin).statusCode());
    }

    @Test
    void nobodyChangesOrDisablesThemselves() {
        assertEquals(403, setRoles(owner, ownerId, version(ownerId), "ACADEMY_OWNER", "INSTRUCTOR").statusCode());
        assertEquals(403, setRoles(admin, adminId, version(adminId), "INSTRUCTOR").statusCode());
        assertEquals(403, disable(owner, ownerId).statusCode());
        assertEquals(403, disable(admin, adminId).statusCode());
        assertEquals(200, api.get("/api/v1/staff-accounts", owner).statusCode());
    }

    @Test
    void roleChangesUseVersionsAndApplyOnTheNextRequest() {
        String address = activeStaff(admin, "INSTRUCTOR");
        Object id = accounts.findByEmail(address).orElseThrow().id();
        String token = (String) HttpApi.parse(passwordLogin(address)).get("accessToken");
        int before = version(id);

        HttpResponse<String> changed = setRoles(admin, id, before, "ANALYST", "ATTENDANCE_OFFICER");

        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals(List.of("ANALYST", "ATTENDANCE_OFFICER"), HttpApi.parse(changed).get("roles"));
        assertEquals(before + 1, ((Number) HttpApi.parse(changed).get("version")).intValue());
        assertEquals(List.of("ANALYST", "ATTENDANCE_OFFICER"), HttpApi.parse(api.get("/api/v1/auth/me", token)).get("roles"),
                "no new sign-in needed");
        assertEquals(409, setRoles(admin, id, before, "INSTRUCTOR").statusCode(), "stale version");
        assertEquals(404, setRoles(admin, UUID.randomUUID(), 0, "INSTRUCTOR").statusCode());
    }

    @Test
    void disablingEndsEverySessionImmediately() {
        String address = activeStaff(admin, "INSTRUCTOR");
        Map<String, Object> session = HttpApi.parse(passwordLogin(address));
        String access = (String) session.get("accessToken");
        assertEquals(200, api.get("/api/v1/auth/me", access).statusCode());
        Object id = accounts.findByEmail(address).orElseThrow().id();

        HttpResponse<String> response = disable(admin, id);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("DISABLED", HttpApi.parse(response).get("status"));
        assertEquals(401, api.get("/api/v1/auth/me", access).statusCode());
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", (String) session.get("refreshToken"))))
                .statusCode());
        assertEquals(401, passwordLogin(address).statusCode());
        assertEquals(200, disable(admin, id).statusCode(), "disabling again is harmless");
        assertEquals(1, auditCount("STAFF_ACCOUNT_DISABLED"));
    }

    @Test
    void twoOwnersRemovingEachOtherAtOnceLeaveOneOwner() throws Exception {
        String secondEmail = email("owner");
        UUID secondId = accountIdOf(secondEmail, academyId, Role.ACADEMY_OWNER);
        String second = login(secondEmail);
        int secondVersion = version(secondId);
        int ownerVersion = version(ownerId);
        List<Callable<Integer>> attempts = List.of(
                () -> setRoles(owner, secondId, secondVersion, "INSTRUCTOR").statusCode(),
                () -> setRoles(second, ownerId, ownerVersion, "INSTRUCTOR").statusCode());

        List<Integer> statuses = race(attempts);

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(s -> s == 403).count(), statuses.toString());
        assertEquals(1, activeOwners(), "exactly one active owner remains");
    }

    @Test
    void twoOwnersDisablingEachOtherAtOnceLeaveOneOwner() throws Exception {
        String secondEmail = email("owner");
        UUID secondId = accountIdOf(secondEmail, academyId, Role.ACADEMY_OWNER);
        String second = login(secondEmail);

        List<Integer> statuses = race(List.of(() -> disable(owner, secondId).statusCode(),
                () -> disable(second, ownerId).statusCode()));

        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), statuses.toString());
        assertEquals(1, activeOwners());
    }

    private int activeOwners() {
        return jdbc.queryForObject("SELECT count(*) FROM accounts a JOIN account_roles r ON r.account_id = a.id "
                + "WHERE a.academy_id = ? AND r.role = 'ACADEMY_OWNER' AND a.status = 'ACTIVE'", Integer.class, academyId);
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
    void otherAcademiesAndNonManagersCannotAdministerStaff() {
        Object instructor = created(admin, email("teacher"), "INSTRUCTOR").get("id");
        String outsider = tokenFor(academy("Other Academy"), Role.ACADEMY_OWNER);
        assertEquals(404, api.get("/api/v1/staff-accounts/" + instructor, outsider).statusCode());
        assertEquals(404, setRoles(outsider, instructor, 0, "ANALYST").statusCode());
        assertEquals(404, disable(outsider, instructor).statusCode());
        assertEquals(404, invite(outsider, instructor).statusCode());
        assertEquals(1, HttpApi.parseList(api.get("/api/v1/staff-accounts", outsider)).size(),
                "the outsider sees only their own academy's single owner");

        for (Role role : List.of(Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER, Role.ANALYST, Role.STUDENT)) {
            String token = tokenFor(academyId, role);
            assertEquals(403, api.get("/api/v1/staff-accounts", token).statusCode(), role.name());
            assertEquals(403, createStaff(token, email("x"), "INSTRUCTOR").statusCode(), role.name());
            assertEquals(403, setRoles(token, instructor, 0, "ANALYST").statusCode(), role.name());
            assertEquals(403, disable(token, instructor).statusCode(), role.name());
            assertEquals(403, invite(token, instructor).statusCode(), role.name());
        }
        assertEquals(401, api.get("/api/v1/staff-accounts", null).statusCode());
        assertEquals(401, invite(null, instructor).statusCode());
        assertEquals("PENDING_ACTIVATION", HttpApi.parse(api.get("/api/v1/staff-accounts/" + instructor, admin)).get("status"));
    }

    @Test
    void sensitiveActionsAreAuditedWithoutPersonalDataOrTokens() {
        String address = email("teacher");
        Object id = created(admin, address, "INSTRUCTOR").get("id");
        String token = (String) HttpApi.parse(invite(admin, id)).get("activationToken");
        setRoles(admin, id, 0, "ANALYST");
        disable(admin, id);

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT actor_account_id, action, target_type, target_id, details::text AS details FROM audit_events ORDER BY occurred_at");

        assertEquals(List.of("STAFF_ACCOUNT_CREATED", "INVITATION_ISSUED", "STAFF_ROLES_CHANGED", "STAFF_ACCOUNT_DISABLED"),
                events.stream().map(e -> e.get("action")).toList());
        for (Map<String, Object> event : events) {
            assertEquals(adminId, event.get("actor_account_id"));
            assertEquals("ACCOUNT", event.get("target_type"));
            assertEquals(id, event.get("target_id").toString());
            assertFalse(event.get("details").toString().contains(address), "no email in audit details");
            assertFalse(event.get("details").toString().contains(token), "no token in audit details");
        }
        assertTrue(events.get(2).get("details").toString().contains("\"before\": [\"INSTRUCTOR\"]"), events.get(2).toString());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE audit_events SET action = 'TAMPERED'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM audit_events"));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class));
    }

    private HttpResponse<String> enable(String token, Object accountId, String body) {
        return api.post("/api/v1/staff-accounts/" + accountId + "/enable", body, token);
    }

    @Test
    void aReEnabledAccountSignsInWithItsPasswordButOldSessionsStayEnded() {
        String address = activeStaff(admin, "INSTRUCTOR");
        Map<String, Object> before = HttpApi.parse(passwordLogin(address));
        Object id = accounts.findByEmail(address).orElseThrow().id();
        assertEquals(200, disable(admin, id).statusCode());

        HttpResponse<String> response = enable(admin, id, "");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("ACTIVE", HttpApi.parse(response).get("status"));
        assertEquals(401, api.get("/api/v1/auth/me", (String) before.get("accessToken")).statusCode(), "old tokens stay invalid");
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", (String) before.get("refreshToken"))))
                .statusCode(), "old sessions stay ended");
        HttpResponse<String> again = passwordLogin(address);
        assertEquals(200, again.statusCode(), "signs in with the same password");
        assertEquals(200, api.get("/api/v1/auth/me", (String) HttpApi.parse(again).get("accessToken")).statusCode());

        assertEquals(200, enable(admin, id, "").statusCode(), "enabling an active account changes nothing");
        assertEquals(1, auditCount("STAFF_ACCOUNT_ENABLED"));
        assertEquals("{\"statusNow\": \"ACTIVE\", \"newPasswordRequired\": \"false\"}", jdbc.queryForObject(
                "SELECT details::text FROM audit_events WHERE action = 'STAFF_ACCOUNT_ENABLED'", String.class));
    }

    @Test
    void reEnablingCanRequireANewPasswordFromAFreshInvitation() {
        String address = activeStaff(admin, "INSTRUCTOR");
        Object id = accounts.findByEmail(address).orElseThrow().id();
        assertEquals(200, disable(admin, id).statusCode());

        HttpResponse<String> response = enable(admin, id, "{\"requireNewPassword\":true}");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("PENDING_ACTIVATION", HttpApi.parse(response).get("status"));
        assertEquals(401, passwordLogin(address).statusCode(), "the old password no longer works");
        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(admin, id)).get("activationToken")));
        assertEquals(200, passwordLogin(address).statusCode());
    }

    @Test
    void anAccountDisabledBeforeActivationReturnsToPending() {
        Object id = created(admin, email("pending"), "INSTRUCTOR").get("id");
        String token = (String) HttpApi.parse(invite(admin, id)).get("activationToken");
        assertEquals(200, disable(admin, id).statusCode());

        assertEquals("PENDING_ACTIVATION", HttpApi.parse(enable(admin, id, "")).get("status"));
        assertEquals(400, activateWithInvitation(token), "the invitation issued before the disable no longer works");
        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(admin, id)).get("activationToken")));
    }

    @Test
    void onlyOwnersReEnableOwnersAndAdministratorsAndNobodyOutsideTheAcademy() {
        String adminAddress = activeStaff(owner, "ACADEMY_ADMIN");
        Object otherAdmin = accounts.findByEmail(adminAddress).orElseThrow().id();
        assertEquals(200, disable(owner, otherAdmin).statusCode());
        assertEquals(403, enable(admin, otherAdmin, "").statusCode(), "an administrator cannot re-enable an administrator");
        assertEquals(403, enable(tokenFor(academyId, Role.INSTRUCTOR), otherAdmin, "").statusCode());
        assertEquals(404, enable(tokenFor(academy("Other Academy"), Role.ACADEMY_OWNER), otherAdmin, "").statusCode());
        assertEquals("DISABLED", HttpApi.parse(api.get("/api/v1/staff-accounts/" + otherAdmin, owner)).get("status"));
        assertEquals(200, enable(owner, otherAdmin, "").statusCode());

        Map<String, Object> studentRecord = new com.physicscube.luma.support.AcademyFixtures(api, admin)
                .student("PCA-1", "student.enable@example.invalid");
        assertEquals(404, enable(admin, studentRecord.get("accountId"), "").statusCode(), "students are not staff accounts");
        assertEquals(404, enable(admin, UUID.randomUUID(), "").statusCode());
    }
}
