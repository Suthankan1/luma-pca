package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Invitations emailed to the invitee; the link never reaches the administrator. */
@TestPropertySource(properties = {
    "luma.mail.enabled=true",
    "luma.mail.from=no-reply@example.invalid",
    "luma.mail.web-origin=https://luma.example.invalid",
    "spring.mail.host=localhost"})
@Import(CapturedMail.class)
class InvitationEmailHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final Pattern LINK = Pattern.compile("^https://luma\\.example\\.invalid/activate#token=([A-Za-z0-9_-]{43})$");

    String admin;
    UUID academyId;

    @BeforeEach
    void seed() {
        CapturedMail.SENT.clear();
        academyId = academy("Mail Academy");
        admin = tokenFor(academyId, Role.STAFF);
    }

    private UUID pendingTeacher(String email) {
        HttpResponse<String> created = api.post("/api/v1/staff-accounts", "{\"email\":\"" + email + "\",\"roles\":[\"INSTRUCTOR\"]}", admin);
        assertEquals(201, created.statusCode(), created.body());
        return UUID.fromString((String) HttpApi.parse(created).get("id"));
    }

    private HttpResponse<String> invite(String token, UUID accountId, String body) {
        return api.post("/api/v1/accounts/" + accountId + "/invitations", body, token);
    }

    private String emailedToken(String to) throws InterruptedException {
        CapturedMail.Mail mail = CapturedMail.SENT.poll(5, TimeUnit.SECONDS);
        assertNotNull(mail, "an invitation email was expected");
        assertEquals("invitation", mail.kind());
        assertEquals(to, mail.to());
        Matcher matcher = LINK.matcher(mail.link());
        assertTrue(matcher.matches(), mail.link());
        return matcher.group(1);
    }

    private void assertNoMail() throws InterruptedException {
        assertNull(CapturedMail.SENT.poll(500, TimeUnit.MILLISECONDS), "no email was expected");
    }

    @Test
    void anEmailedInvitationReachesOnlyTheInviteeAndActivatesTheAccount() throws Exception {
        UUID teacher = pendingTeacher("teacher@example.invalid");
        HttpResponse<String> response = invite(admin, teacher, "{\"delivery\":\"EMAIL\"}");

        assertEquals(201, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        Map<String, Object> body = HttpApi.parse(response);
        assertEquals("EMAIL", body.get("delivery"));
        assertNull(body.get("activationToken"));
        assertNull(body.get("activationPath"));
        assertNotNull(body.get("expiresAt"));

        String token = emailedToken("teacher@example.invalid");
        assertEquals(204, activateWithInvitation(token));
        assertEquals(200, passwordLogin("teacher@example.invalid").statusCode());
        // Audited with the delivery method; never the address or the token.
        Map<String, Object> audit = jdbc.queryForMap("SELECT details->>'delivery' AS delivery, details::text AS text "
                + "FROM audit_events WHERE action = 'INVITATION_ISSUED'");
        assertEquals("EMAIL", audit.get("delivery"));
        assertTrue(!audit.get("text").toString().contains("teacher@") && !audit.get("text").toString().contains(token));
    }

    @Test
    void aNewInvitationRevokesTheEmailedOne() throws Exception {
        UUID teacher = pendingTeacher("teacher@example.invalid");
        invite(admin, teacher, "{\"delivery\":\"EMAIL\"}");
        String first = emailedToken("teacher@example.invalid");
        invite(admin, teacher, "{\"delivery\":\"EMAIL\"}");
        String second = emailedToken("teacher@example.invalid");
        assertEquals(400, activateWithInvitation(first));
        assertEquals(204, activateWithInvitation(second));
    }

    @Test
    void linkDeliveryStaysTheDefaultAndSendsNothing() throws Exception {
        UUID teacher = pendingTeacher("teacher@example.invalid");
        for (String body : new String[] {"", "{}", "{\"delivery\":\"LINK\"}"}) {
            HttpResponse<String> response = invite(admin, teacher, body);
            assertEquals(201, response.statusCode(), body);
            assertEquals("LINK", HttpApi.parse(response).get("delivery"));
            assertTrue(((String) HttpApi.parse(response).get("activationToken")).matches("[A-Za-z0-9_-]{43}"));
        }
        assertNoMail();
    }

    @Test
    void refusedInvitationsSendNothing() throws Exception {
        UUID teacher = pendingTeacher("teacher@example.invalid");
        assertEquals(400, invite(admin, teacher, "{\"delivery\":\"SMS\"}").statusCode());
        String student = tokenFor(academyId, Role.STUDENT);
        assertEquals(403, invite(student, teacher, "{\"delivery\":\"EMAIL\"}").statusCode());
        UUID active = accountIdOf("active@example.invalid", academyId, Role.STAFF);
        assertEquals(409, invite(admin, active, "{\"delivery\":\"EMAIL\"}").statusCode());
        assertEquals(404, invite(admin, UUID.randomUUID(), "{\"delivery\":\"EMAIL\"}").statusCode());
        assertNoMail();
    }
}
