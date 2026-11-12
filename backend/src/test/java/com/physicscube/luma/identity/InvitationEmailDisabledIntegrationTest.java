package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The default configuration: no SMTP server, so invitations can only be links. */
class InvitationEmailDisabledIntegrationTest extends StaffApiIntegrationTest {
    @Test
    void emailDeliveryIsRefusedBeforeAnythingChangesAndLinksStillWork() {
        UUID academyId = academy("No Mail Academy");
        String admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        HttpResponse<String> created = api.post("/api/v1/staff-accounts", "{\"email\":\"teacher@example.invalid\",\"roles\":[\"INSTRUCTOR\"]}", admin);
        String id = (String) HttpApi.parse(created).get("id");

        HttpResponse<String> emailed = api.post("/api/v1/accounts/" + id + "/invitations", "{\"delivery\":\"EMAIL\"}", admin);
        assertEquals(503, emailed.statusCode());
        assertEquals("Email is not set up for this academy yet. Create a link to copy instead.", HttpApi.parse(emailed).get("detail"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_invitations", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'INVITATION_ISSUED'", Integer.class));

        assertEquals(201, api.post("/api/v1/accounts/" + id + "/invitations", "", admin).statusCode());

        HttpResponse<String> bulk = api.post("/api/v1/student-invitations", "{\"dryRun\":true}", admin);
        assertEquals(503, bulk.statusCode(), "bulk invitations need email");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitation_emails", Integer.class));
    }
}
