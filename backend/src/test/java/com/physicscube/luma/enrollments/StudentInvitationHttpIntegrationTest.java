package com.physicscube.luma.enrollments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.CapturedMail;
import com.physicscube.luma.identity.InvitationEmailSender;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.AcademyFixtures;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** Bulk student invitations: queued by the API, sent by the background sender (driven directly here). */
@TestPropertySource(properties = {
    "luma.mail.enabled=true",
    "luma.mail.from=no-reply@example.invalid",
    "luma.mail.web-origin=https://luma.example.invalid",
    "spring.mail.host=localhost"})
@Import(CapturedMail.class)
class StudentInvitationHttpIntegrationTest extends StaffApiIntegrationTest {
    private static final Pattern LINK = Pattern.compile("^https://luma\\.example\\.invalid/activate#token=([A-Za-z0-9_-]{43})$");

    @Autowired
    InvitationEmailSender sender;

    UUID academyId;
    String admin;
    AcademyFixtures fixtures;
    String batch;

    @BeforeEach
    void seed() {
        CapturedMail.SENT.clear();
        CapturedMail.FAIL_NEXT.set(0);
        academyId = academy("Invite Academy");
        admin = tokenFor(academyId, Role.ACADEMY_ADMIN);
        fixtures = new AcademyFixtures(api, admin);
        batch = fixtures.batch(fixtures.course("PHY-2027"), "MON");
    }

    /** A student's account id; enrolled in {@code batchId} when given. */
    private String student(String number, String batchId) {
        Map<String, Object> created = fixtures.student(number, number.toLowerCase() + "@example.invalid");
        if (batchId != null) {
            assertEquals(201, fixtures.enroll(batchId, (String) created.get("id"), "2026-01-05").statusCode());
        }
        return (String) created.get("accountId");
    }

    private Map<String, Object> invite(String body) {
        HttpResponse<String> response = api.post("/api/v1/student-invitations", body, admin);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }

    private static Map<String, Object> counts(int pending, int queued, int alreadyQueued, int alreadyInvited) {
        return Map.of("pending", pending, "queued", queued, "alreadyQueued", alreadyQueued, "alreadyInvited", alreadyInvited);
    }

    private Map<String, Object> progress() {
        return HttpApi.parse(api.get("/api/v1/student-invitations/progress", admin));
    }

    private List<CapturedMail.Mail> drainMail() {
        List<CapturedMail.Mail> mail = new ArrayList<>();
        CapturedMail.SENT.drainTo(mail);
        return mail;
    }

    private void makeDue() {
        jdbc.update("UPDATE invitation_emails SET next_attempt_at = now() - interval '1 second' WHERE status = 'QUEUED'");
    }

    @Test
    void academyPlaceholderAddressesAreNeverEmailed() {
        student("PCA-REAL", batch);
        Map<String, Object> placeholder = fixtures.student("PCA-NOMAIL", "pca-nomail@students.academy.invalid");
        assertEquals(201, fixtures.enroll(batch, (String) placeholder.get("id"), "2026-01-05").statusCode());
        assertEquals(counts(1, 1, 0, 0), invite("{\"batchId\":\"" + batch + "\",\"dryRun\":true}"));
    }

    @Test
    void aBatchsCurrentStudentsAreEmailedOnceAndTheirLinksActivate() {
        student("PCA-1", batch);
        student("PCA-2", batch);
        student("PCA-LEFT", batch);
        jdbc.update("UPDATE enrollments SET withdrawn_on = current_date - 1 WHERE student_id = (SELECT id FROM students WHERE student_number = 'PCA-LEFT')");
        student("PCA-INACTIVE", batch);
        jdbc.update("UPDATE students SET active = false WHERE student_number = 'PCA-INACTIVE'");
        student("PCA-OTHER", fixtures.batch(fixtures.course("CHEM-2027"), "TUE"));
        String activated = student("PCA-ACTIVE", batch);
        activate(UUID.fromString(activated));

        assertEquals(counts(2, 2, 0, 0), invite("{\"batchId\":\"" + batch + "\",\"dryRun\":true}"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitation_emails", Integer.class), "a dry run queues nothing");

        assertEquals(counts(2, 2, 0, 0), invite("{\"batchId\":\"" + batch + "\"}"));
        assertEquals(counts(2, 0, 2, 0), invite("{\"batchId\":\"" + batch + "\"}"), "asking again while they wait queues nothing more");
        assertEquals(Map.of("waiting", 2, "sent", 0, "failed", 0, "cancelled", 0), progress());
        assertTrue(drainMail().isEmpty(), "nothing is sent in the request itself");

        assertEquals(2, sender.runOnce());
        List<CapturedMail.Mail> mail = drainMail();
        assertEquals(Set.of("pca-1@example.invalid", "pca-2@example.invalid"), Set.copyOf(mail.stream().map(CapturedMail.Mail::to).toList()));
        for (CapturedMail.Mail one : mail) {
            Matcher link = LINK.matcher(one.link());
            assertTrue(link.matches(), one.link());
            assertEquals(204, activateWithInvitation(link.group(1)), "each emailed link activates the account");
        }
        assertEquals(0, sender.runOnce());
        assertEquals(Map.of("waiting", 0, "sent", 2, "failed", 0, "cancelled", 0), progress());

        // One audit record for the request and one per issued link; no addresses or links in either.
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'INVITATIONS_QUEUED' AND target_type = 'BATCH'",
                Integer.class));
        List<String> details = jdbc.queryForList("SELECT details::text FROM audit_events WHERE action IN ('INVITATIONS_QUEUED', 'INVITATION_ISSUED')",
                String.class);
        assertEquals(3, details.size());
        details.forEach(d -> assertFalse(d.contains("@") || d.contains("token"), d));
    }

    @Test
    void theWholeAcademySkipsThoseAlreadyInvitedUnlessResendingAndNeverStaff() {
        String linked = student("PCA-LINK", null);
        student("PCA-NEW", batch);
        assertEquals(201, api.post("/api/v1/staff-accounts", "{\"email\":\"teacher@example.invalid\",\"roles\":[\"INSTRUCTOR\"]}", admin)
                .statusCode());
        assertEquals(201, api.post("/api/v1/accounts/" + linked + "/invitations", "", admin).statusCode(), "a copied link, still usable");

        assertEquals(counts(2, 1, 0, 1), invite("{}"));
        assertEquals(counts(2, 1, 1, 0), invite("{\"resend\":true}"));
        sender.runOnce();
        assertEquals(Set.of("pca-link@example.invalid", "pca-new@example.invalid"),
                Set.copyOf(drainMail().stream().map(CapturedMail.Mail::to).toList()), "the staff member is never included");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account_invitations i JOIN accounts a ON a.id = i.account_id "
                + "WHERE a.email = 'pca-link@example.invalid' AND i.consumed_at IS NULL AND i.revoked_at IS NULL", Integer.class),
                "resending replaced the copied link");
    }

    @Test
    void failedSendsAreRetriedThenGivenUpAndCanBeQueuedAgain() {
        student("PCA-1", batch);
        CapturedMail.FAIL_NEXT.set(1);
        invite("{}");

        assertEquals(1, sender.runOnce());
        assertTrue(drainMail().isEmpty());
        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempts, last_error, next_attempt_at > now() AS later FROM invitation_emails");
        assertEquals(Map.of("status", "QUEUED", "attempts", 1, "last_error", "IllegalStateException", "later", true), row);
        assertEquals(0, sender.runOnce(), "not due before the back-off ends");
        makeDue();
        assertEquals(1, sender.runOnce());
        assertEquals(1, drainMail().size());
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM invitation_emails", String.class));

        // Five failures (the default limit) give up; the failed account is then queued again even without resend.
        student("PCA-2", batch);
        jdbc.update("DELETE FROM invitation_emails");
        CapturedMail.FAIL_NEXT.set(5);
        assertEquals(counts(2, 1, 0, 1), invite("{}"), "PCA-1 holds a usable link already");
        for (int i = 0; i < 5; i++) {
            makeDue();
            assertEquals(1, sender.runOnce());
        }
        assertTrue(drainMail().isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM invitation_emails", String.class));
        assertEquals(1, ((Number) progress().get("failed")).intValue());
        List<Object> failed = HttpApi.parseList(api.get("/api/v1/student-invitations/failures", admin));
        assertEquals(1, failed.size(), "staff see which student's email failed");
        Map<?, ?> failure = (Map<?, ?>) failed.get(0);
        assertEquals("PCA-2", failure.get("studentNumber"));
        assertEquals("pca-2@example.invalid", failure.get("email"));
        assertEquals("IllegalStateException", failure.get("reason"));
        assertEquals(5, ((Number) failure.get("attempts")).intValue());
        assertEquals(403, api.get("/api/v1/student-invitations/failures", tokenFor(academyId, Role.INSTRUCTOR)).statusCode());
        assertEquals(counts(2, 1, 0, 1), invite("{}"), "a failed email does not count as invited");
        assertEquals(List.of(), HttpApi.parseList(api.get("/api/v1/student-invitations/failures", admin)), "queued again: no longer listed");
        sender.runOnce();
        assertEquals(List.of("pca-2@example.invalid"), drainMail().stream().map(CapturedMail.Mail::to).toList());
        assertEquals(List.of(), HttpApi.parseList(api.get("/api/v1/student-invitations/failures", admin)));
    }

    @Test
    void anAccountActivatedWhileWaitingIsNotEmailed() {
        String account = student("PCA-1", batch);
        invite("{}");
        activate(UUID.fromString(account));

        assertEquals(1, sender.runOnce());
        assertNull(CapturedMail.SENT.poll());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM invitation_emails", String.class));
        assertEquals(1, ((Number) progress().get("cancelled")).intValue());
    }

    @Test
    void severalSendersTogetherEmailEachStudentExactlyOnce() {
        for (int i = 1; i <= 30; i++) {
            student(String.format("PCA-%02d", i), batch);
        }
        assertEquals(30, ((Number) invite("{\"batchId\":\"" + batch + "\"}").get("queued")).intValue());

        CompletableFuture<?>[] workers = new CompletableFuture<?>[3];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = CompletableFuture.runAsync(() -> {
                while (sender.runOnce() > 0) {
                    Thread.onSpinWait();
                }
            });
        }
        CompletableFuture.allOf(workers).join();

        List<String> recipients = drainMail().stream().map(CapturedMail.Mail::to).toList();
        assertEquals(30, recipients.size());
        assertEquals(30, new HashSet<>(recipients).size(), "no student emailed twice");
        assertEquals(30, jdbc.queryForObject("SELECT count(*) FROM invitation_emails WHERE status = 'SENT'", Integer.class));
    }

    @Test
    void refusals() {
        String body = "{\"dryRun\":true}";
        assertEquals(403, api.post("/api/v1/student-invitations", body, tokenFor(academyId, Role.INSTRUCTOR)).statusCode());
        assertEquals(403, api.get("/api/v1/student-invitations/progress", tokenFor(academyId, Role.ANALYST)).statusCode());
        assertEquals(401, api.post("/api/v1/student-invitations", body, null).statusCode());

        UUID other = academy("Other Academy");
        AcademyFixtures otherFixtures = new AcademyFixtures(api, tokenFor(other, Role.ACADEMY_ADMIN));
        String otherBatch = otherFixtures.batch(otherFixtures.course("OTH-1"), "X");
        assertEquals(404, api.post("/api/v1/student-invitations", "{\"batchId\":\"" + otherBatch + "\"}", admin).statusCode());

        // Another academy's pending students are never reached from this academy.
        otherFixtures.student("PCA-OTHER", "other@example.invalid");
        assertEquals(counts(0, 0, 0, 0), invite("{}"));
    }
}
