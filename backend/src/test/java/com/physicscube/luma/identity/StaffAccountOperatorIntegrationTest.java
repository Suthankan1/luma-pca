package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * No staff page (owner decision 2026-11-20): the staff-account API is gone, the operator blocks and unblocks staff with
 * the runbook SQL (docs/delivery/runbooks/sql), and staff still issue invitations for pending accounts.
 */
class StaffAccountOperatorIntegrationTest extends StaffApiIntegrationTest {
    private static final AtomicInteger PEOPLE = new AtomicInteger();

    UUID academyId;
    String staff;

    @BeforeEach
    void seed() {
        academyId = academy("Physics Cube Test");
        staff = tokenFor(academyId, Role.STAFF);
    }

    private static String email(String prefix) {
        return prefix + PEOPLE.incrementAndGet() + "@example.invalid";
    }

    private HttpResponse<String> invite(String token, Object accountId) {
        return api.post("/api/v1/accounts/" + accountId + "/invitations", "", token);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
    }

    @Test
    void theStaffAccountRoutesAreGone() {
        UUID colleague = accountIdOf(email("colleague"), academyId, Role.STAFF);
        String student = tokenFor(academyId, Role.STUDENT);
        for (String token : new String[] {staff, student}) {
            assertEquals(403, api.get("/api/v1/staff-accounts", token).statusCode());
            assertEquals(403, api.post("/api/v1/staff-accounts", HttpApi.json(Map.of("email", email("x"))), token).statusCode());
            assertEquals(403, api.get("/api/v1/staff-accounts/" + colleague, token).statusCode());
            assertEquals(403, api.post("/api/v1/staff-accounts/" + colleague + "/disable", "", token).statusCode());
            assertEquals(403, api.post("/api/v1/staff-accounts/" + colleague + "/enable", "", token).statusCode());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ? AND status = 'ACTIVE'", Integer.class, colleague));
    }

    @Test
    void blockingEndsEverySessionAtOnceAndIsAudited() {
        String address = email("leaver");
        accountIdOf(address, academyId, Role.STAFF);
        Map<String, Object> session = loginSession(address);
        String access = (String) session.get("accessToken");
        assertEquals(200, api.get("/api/v1/auth/me", access).statusCode());

        runOperatorSql("staff-block.sql", address.toUpperCase());

        assertEquals(401, api.get("/api/v1/auth/me", access).statusCode(), "the next request is refused");
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", (String) session.get("refreshToken"))))
                .statusCode());
        assertEquals(401, passwordLogin(address).statusCode());
        assertEquals(1, auditCount("STAFF_ACCOUNT_DISABLED"));
        assertEquals(null, jdbc.queryForObject("SELECT actor_account_id FROM audit_events WHERE action = 'STAFF_ACCOUNT_DISABLED'", UUID.class));
        runOperatorSql("staff-block.sql", address);
        assertEquals(1, auditCount("STAFF_ACCOUNT_DISABLED"), "blocking again changes nothing");
    }

    @Test
    void unblockingRestoresThePasswordButNotTheOldSessions() {
        String address = email("returning");
        accountIdOf(address, academyId, Role.STAFF);
        Map<String, Object> before = loginSession(address);
        runOperatorSql("staff-block.sql", address);

        runOperatorSql("staff-unblock.sql", address);

        assertEquals(401, api.get("/api/v1/auth/me", (String) before.get("accessToken")).statusCode(), "old tokens stay invalid");
        assertEquals(200, passwordLogin(address).statusCode(), "signs in with the same password");
        assertEquals("{\"by\": \"operator\", \"statusNow\": \"ACTIVE\", \"newPasswordRequired\": \"false\"}", jdbc.queryForObject(
                "SELECT details::text FROM audit_events WHERE action = 'STAFF_ACCOUNT_ENABLED'", String.class));
    }

    @Test
    void anAccountBlockedBeforeActivatingWaitsForANewInvitation() {
        String address = email("pending");
        UUID id = pendingStaff(address, academyId);
        String earlier = (String) HttpApi.parse(invite(staff, id)).get("activationToken");
        runOperatorSql("staff-block.sql", address);
        runOperatorSql("staff-unblock.sql", address);

        assertEquals("PENDING_ACTIVATION", jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, id));
        assertEquals(400, activateWithInvitation(earlier), "the invitation issued before the block no longer works");
        assertEquals(204, activateWithInvitation((String) HttpApi.parse(invite(staff, id)).get("activationToken")));
    }

    @Test
    void theRunbookSqlNeverTouchesStudentsOrUnknownAddresses() {
        Map<String, Object> student = new AcademyFixtures(api, staff).student("PCA-1", "learner@example.invalid");
        activate(UUID.fromString((String) student.get("accountId")));
        runOperatorSql("staff-block.sql", "learner@example.invalid");
        runOperatorSql("staff-block.sql", "nobody@example.invalid");
        assertEquals(200, passwordLogin("learner@example.invalid").statusCode());
        assertEquals(0, auditCount("STAFF_ACCOUNT_DISABLED"));
    }

    @Test
    void staffInviteAPendingAccountEndToEndAndAReissueRevokesTheEarlierLink() {
        String address = email("teacher");
        UUID id = pendingStaff(address, academyId);
        assertEquals(401, passwordLogin(address).statusCode(), "pending accounts cannot sign in");

        HttpResponse<String> first = invite(staff, id);
        assertEquals(201, first.statusCode(), first.body());
        assertTrue(first.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        String earlier = (String) HttpApi.parse(first).get("activationToken");
        assertTrue(earlier.matches("[A-Za-z0-9_-]{43}"));
        assertEquals("/activate#token=" + earlier, HttpApi.parse(first).get("activationPath"));
        String later = (String) HttpApi.parse(invite(staff, id)).get("activationToken");
        assertNotEquals(earlier, later);
        assertEquals(400, activateWithInvitation(earlier));
        assertEquals(204, activateWithInvitation(later));
        assertEquals(200, passwordLogin(address).statusCode());
        assertEquals(409, invite(staff, id).statusCode(), "an active account needs no invitation");

        String details = jdbc.queryForList("SELECT details::text FROM audit_events WHERE target_id = ?", String.class, id).toString();
        assertFalse(details.contains(address), "no email in audit details");
        assertFalse(details.contains(later), "no token in audit details");
    }

    @Test
    void invitationsStayInsideTheAcademyAndAreStaffOnly() {
        UUID id = pendingStaff(email("teacher"), academyId);
        assertEquals(404, invite(tokenFor(academy("Other Academy"), Role.STAFF), id).statusCode());
        assertEquals(403, invite(tokenFor(academyId, Role.STUDENT), id).statusCode());
        assertEquals(401, invite(null, id).statusCode());
        assertEquals(404, invite(staff, UUID.randomUUID()).statusCode());
        assertEquals(0, auditCount("INVITATION_ISSUED"));
    }
}
