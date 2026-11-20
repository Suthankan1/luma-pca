package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OwnerBootstrapIntegrationTest extends StaffApiIntegrationTest {
    private static final String ACADEMY = "Physics Cube Academy";
    private static final String OWNER = "owner@example.invalid";

    @Autowired
    OwnerBootstrapService bootstrap;

    private int academies() {
        return jdbc.queryForObject("SELECT count(*) FROM academies", Integer.class);
    }

    @Test
    void aNewAcademyGetsAPendingOwnerWhoActivatesAndMustEnrolTwoStepSignIn() {
        OwnerBootstrapService.Result result = bootstrap.bootstrap("  " + ACADEMY + " ", " Owner@Example.INVALID ");

        assertTrue(result.academyCreated());
        assertTrue(result.ownerCreated());
        assertEquals(ACADEMY, jdbc.queryForObject("SELECT name FROM academies WHERE id = ?", String.class, result.academyId()));
        Map<String, Object> owner = jdbc.queryForMap("SELECT email, status, academy_id FROM accounts WHERE id = ?", result.ownerId());
        assertEquals(OWNER, owner.get("email"));
        assertEquals("PENDING_ACTIVATION", owner.get("status"));
        assertEquals(List.of("ACADEMY_OWNER"),
                jdbc.queryForList("SELECT role FROM account_roles WHERE account_id = ?", String.class, result.ownerId()));
        assertFalse(result.toString().contains(result.token()), "the token is redacted from toString");

        Map<String, Object> audit = jdbc.queryForMap("SELECT actor_account_id, target_id, details->>'academyCreated' AS created, "
                + "details::text AS text FROM audit_events WHERE action = 'OWNER_BOOTSTRAPPED'");
        assertNull(audit.get("actor_account_id"), "done by the operator, not an account");
        assertEquals(result.ownerId(), audit.get("target_id"));
        assertEquals("true", audit.get("created"));
        assertFalse(audit.get("text").toString().contains(OWNER));

        assertEquals(204, activateWithInvitation(result.token()));
        HttpResponse<String> login = passwordLogin(OWNER);
        assertEquals(200, login.statusCode(), login.body());
        assertNotNull(HttpApi.parse(login).get("accessToken"), "owners sign in with their password alone");
    }

    @Test
    void runningAgainBeforeActivationOnlyReplacesTheInvitation() {
        OwnerBootstrapService.Result first = bootstrap.bootstrap(ACADEMY, OWNER);
        OwnerBootstrapService.Result second = bootstrap.bootstrap(ACADEMY, OWNER);

        assertFalse(second.academyCreated());
        assertFalse(second.ownerCreated());
        assertEquals(first.ownerId(), second.ownerId());
        assertEquals(1, academies());
        assertEquals(400, activateWithInvitation(first.token()), "the earlier link is cancelled");
        assertEquals(204, activateWithInvitation(second.token()));
    }

    @Test
    void anActiveOwnerIsNeverReplaced() {
        OwnerBootstrapService.Result result = bootstrap.bootstrap(ACADEMY, OWNER);
        activateWithInvitation(result.token());

        var refused = assertThrows(OwnerBootstrapService.RefusedException.class, () -> bootstrap.bootstrap(ACADEMY, "other@example.invalid"));
        assertTrue(refused.getMessage().startsWith("This academy already has an active owner."));
        assertThrows(OwnerBootstrapService.RefusedException.class, () -> bootstrap.bootstrap(ACADEMY, OWNER));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = 'other@example.invalid'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account_invitations WHERE account_id = ?",
                Integer.class, result.ownerId()), "the refused runs issued no new invitation");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'OWNER_BOOTSTRAPPED'", Integer.class));
    }

    @Test
    void otherConflictsAreRefusedWithoutChangingAnything() {
        bootstrap.bootstrap(ACADEMY, OWNER);
        var pendingForOther = assertThrows(OwnerBootstrapService.RefusedException.class,
                () -> bootstrap.bootstrap(ACADEMY, "someone.else@example.invalid"));
        assertTrue(pendingForOther.getMessage().startsWith("An owner invitation is already pending for another address."));

        UUID other = academy("Other Academy");
        accountIdOf("taken@example.invalid", other, Role.STUDENT);
        var taken = assertThrows(OwnerBootstrapService.RefusedException.class, () -> bootstrap.bootstrap("New Academy", "taken@example.invalid"));
        assertTrue(taken.getMessage().startsWith("That address already belongs to another LUMA account."));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM academies WHERE name = 'New Academy'", Integer.class),
                "the academy created in the refused run is rolled back");

        academy("Twin");
        academy("Twin");
        assertThrows(OwnerBootstrapService.RefusedException.class, () -> bootstrap.bootstrap("Twin", "twin@example.invalid"));
        assertThrows(OwnerBootstrapService.RefusedException.class, () -> bootstrap.bootstrap(" ", "x@example.invalid"));
        assertThrows(OwnerBootstrapService.RefusedException.class, () -> bootstrap.bootstrap("x".repeat(201), "x@example.invalid"));
        assertThrows(OwnerBootstrapService.RefusedException.class, () -> bootstrap.bootstrap(ACADEMY, "not an email"));
    }

    @Test
    void anExistingAcademyWithoutAnOwnerGetsOne() {
        UUID existing = academy(ACADEMY);
        accountIdOf("admin@example.invalid", existing, Role.ACADEMY_ADMIN);
        OwnerBootstrapService.Result result = bootstrap.bootstrap(ACADEMY, OWNER);
        assertEquals(existing, result.academyId());
        assertFalse(result.academyCreated());
        assertTrue(result.ownerCreated());
    }

    @Test
    void concurrentRunsCreateOneAcademyAndOneOwner() throws Exception {
        int threads = 6;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<OwnerBootstrapService.Result>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    start.await();
                    return bootstrap.bootstrap(ACADEMY, OWNER);
                });
            }
            List<OwnerBootstrapService.Result> results = new ArrayList<>();
            for (Future<OwnerBootstrapService.Result> future : pool.invokeAll(tasks)) {
                results.add(future.get());
            }
            assertEquals(1, results.stream().filter(OwnerBootstrapService.Result::academyCreated).count());
            assertEquals(1, results.stream().filter(OwnerBootstrapService.Result::ownerCreated).count());
            assertEquals(1, results.stream().map(OwnerBootstrapService.Result::ownerId).distinct().count());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, academies());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
    }

    private record Run(int code, String output) {
    }

    private Run command(String academy, String email, String delivery, String origin, MailProperties mail, AccountMailer mailer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int[] exited = {-1};
        OwnerBootstrapCommand command = new OwnerBootstrapCommand(bootstrap, mail, mailer, academy, email, delivery, origin,
                new PrintStream(bytes, true, StandardCharsets.UTF_8), code -> exited[0] = code);
        command.run(null);
        return new Run(exited[0], bytes.toString(StandardCharsets.UTF_8));
    }

    private static final MailProperties NO_MAIL = new MailProperties(false, null, null);

    private static AccountMailer failingMailer() {
        return new AccountMailer() {
            @Override
            public void invitation(String to, String link, Instant expiresAt) {
                throw new IllegalStateException("not in this test");
            }

            @Override
            public void passwordReset(String to, String link, Instant expiresAt) {
                throw new IllegalStateException("not in this test");
            }
        };
    }

    @Test
    void theCommandPrintsTheLinkOnlyToItsOwnOutput(CapturedOutput log) {
        Run run = command(ACADEMY, OWNER, "PRINT", "https://luma.example.invalid", NO_MAIL, failingMailer());
        assertEquals(0, run.code(), run.output());
        String link = run.output().lines().filter(line -> line.startsWith("https://")).findFirst().orElseThrow();
        assertTrue(link.matches("https://luma\\.example\\.invalid/activate#token=[A-Za-z0-9_-]{43}"), link);
        String token = link.substring(link.indexOf("token=") + 6);
        assertFalse(log.getAll().contains(token), "the link never reaches the log");
        assertEquals(204, activateWithInvitation(token));

        Run again = command(ACADEMY, OWNER, "PRINT", "", NO_MAIL, failingMailer());
        assertEquals(1, again.code());
        assertTrue(again.output().startsWith("LUMA owner bootstrap refused: This academy already has an active owner."));
    }

    @Test
    void theCommandEmailsTheLinkOrExplainsWhyNot() {
        assertEquals(2, command(ACADEMY, OWNER, "EMAIL", "", NO_MAIL, failingMailer()).code(), "email not configured");
        assertEquals(2, command(ACADEMY, OWNER, "SMS", "", NO_MAIL, failingMailer()).code());
        assertEquals(2, command(ACADEMY, OWNER, "PRINT", "http://luma.example.invalid", NO_MAIL, failingMailer()).code());
        assertEquals(0, academies(), "nothing is created for refused arguments");

        MailProperties mail = new MailProperties(true, "no-reply@example.invalid", "https://luma.example.invalid");
        List<String> sent = new ArrayList<>();
        AccountMailer capturing = new AccountMailer() {
            @Override
            public void invitation(String to, String link, Instant expiresAt) {
                sent.add(to + " " + link);
            }

            @Override
            public void passwordReset(String to, String link, Instant expiresAt) {
            }
        };
        Run emailed = command(ACADEMY, " Owner@Example.invalid", "EMAIL", "", mail, capturing);
        assertEquals(0, emailed.code(), emailed.output());
        assertFalse(emailed.output().contains("token="), "an emailed link is not printed");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).matches("owner@example\\.invalid https://luma\\.example\\.invalid/activate#token=[A-Za-z0-9_-]{43}"), sent.get(0));

        Run failed = command(ACADEMY, OWNER, "EMAIL", "", mail, failingMailer());
        assertEquals(3, failed.code());
        assertTrue(failed.output().contains("could not be sent"));
    }
}
