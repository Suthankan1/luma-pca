package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Self-service reset over real HTTP and PostgreSQL 17; emails are captured instead of sent. */
@TestPropertySource(properties = {
    "luma.mail.enabled=true",
    "luma.mail.from=no-reply@example.invalid",
    "luma.mail.web-origin=https://luma.example.invalid",
    "spring.mail.host=localhost"})
@Import(CapturedMail.class)
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String OLD = "quiet river under bridge";
    private static final String NEW = "bright lantern over harbour";
    private static final String EMAIL = "student@example.invalid";
    private static final Pattern LINK = Pattern.compile("^https://luma\\.example\\.invalid/reset-password#token=([A-Za-z0-9_-]{43})$");

    private static final java.util.concurrent.BlockingQueue<CapturedMail.Mail> SENT = CapturedMail.SENT;

    @Autowired
    AccountService accounts;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    HttpApi api;
    UUID academyId;
    UUID studentId;

    @BeforeEach
    void seed() {
        api = new HttpApi(port);
        resetDatabase();
        SENT.clear();
        academyId = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Test Academy')", academyId);
        studentId = accounts.register(academyId, EMAIL, Set.of(Role.STUDENT)).id();
        accounts.setPassword(studentId, OLD);
    }

    private HttpResponse<String> request(String email) {
        return api.post("/api/v1/auth/password-reset/request", HttpApi.json(Map.of("email", email)));
    }

    private HttpResponse<String> confirm(String token, String password) {
        return api.post("/api/v1/auth/password-reset/confirm", HttpApi.json(Map.of("token", token, "password", password)));
    }

    private HttpResponse<String> login(String email, String password) {
        return api.post("/api/v1/auth/login", HttpApi.json(Map.of("email", email, "password", password)));
    }

    /** The next captured email's token, after checking where it went and what the link looks like. */
    private String nextToken(String expectedTo) throws InterruptedException {
        CapturedMail.Mail mail = SENT.poll(5, TimeUnit.SECONDS);
        assertNotNull(mail, "a reset email was expected");
        assertEquals("reset", mail.kind());
        assertEquals(expectedTo, mail.to());
        Matcher matcher = LINK.matcher(mail.link());
        assertTrue(matcher.matches(), mail.link());
        return matcher.group(1);
    }

    private void assertNoMail() throws InterruptedException {
        assertNull(SENT.poll(500, TimeUnit.MILLISECONDS), "no reset email was expected");
    }

    @Test
    void anActiveAccountGetsASingleUseLinkThatSetsANewPassword() throws Exception {
        HttpResponse<String> response = request("  Student@Example.INVALID ");
        assertEquals(202, response.statusCode(), response.body());
        assertEquals("", response.body());
        String token = nextToken(EMAIL);

        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM password_resets WHERE account_id = ?", studentId);
        assertEquals(OpaqueToken.digest(token), stored.get("token_hash"));
        Instant created = ((java.sql.Timestamp) stored.get("created_at")).toInstant();
        Instant expires = ((java.sql.Timestamp) stored.get("expires_at")).toInstant();
        assertEquals(Duration.ofMinutes(30), Duration.between(created, expires));

        assertEquals(204, confirm(token, NEW).statusCode());
        assertEquals(401, login(EMAIL, OLD).statusCode());
        assertEquals(200, login(EMAIL, NEW).statusCode());
        HttpResponse<String> again = confirm(token, "another long passphrase here");
        assertEquals(400, again.statusCode());
        assertEquals("This reset link is invalid, already used or expired. Ask for a new one.", HttpApi.parse(again).get("detail"));

        // Audited without the address or the token.
        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT action, actor_account_id, target_id, details::text AS details FROM audit_events ORDER BY occurred_at");
        assertEquals(List.of("PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_COMPLETED"), audit.stream().map(a -> a.get("action")).toList());
        for (Map<String, Object> row : audit) {
            assertEquals(studentId, row.get("actor_account_id"));
            assertEquals(studentId, row.get("target_id"));
            assertEquals("{}", row.get("details"));
        }
    }

    @Test
    void theAnswerIsTheSameWhetherOrNotTheAddressHasAnAccount() throws Exception {
        UUID pending = accounts.register(academyId, "pending@example.invalid", Set.of(Role.STUDENT)).id();
        UUID disabled = accounts.register(academyId, "disabled@example.invalid", Set.of(Role.STUDENT)).id();
        accounts.setPassword(disabled, OLD);
        accounts.disable(disabled);
        for (String email : List.of("nobody@example.invalid", "pending@example.invalid", "disabled@example.invalid", "not an email")) {
            HttpResponse<String> response = request(email);
            assertEquals(202, response.statusCode(), email);
            assertEquals("", response.body(), email);
        }
        assertNoMail();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_resets", Integer.class));
        assertNotNull(pending);
        assertEquals(400, request("").statusCode());
    }

    @Test
    void resettingEndsEverySessionAndClearsALockout() throws Exception {
        Map<String, Object> tokens = HttpApi.parse(login(EMAIL, OLD));
        String access = (String) tokens.get("accessToken");
        String refresh = (String) tokens.get("refreshToken");
        jdbc.update("UPDATE accounts SET failed_login_count = 5, locked_until = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(900)), studentId);
        assertEquals(401, login(EMAIL, OLD).statusCode());

        request(EMAIL);
        assertEquals(204, confirm(nextToken(EMAIL), NEW).statusCode());

        assertEquals(401, api.get("/api/v1/auth/me", access).statusCode());
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", refresh))).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE account_id = ? AND revoked_at IS NULL",
                Integer.class, studentId));
        assertEquals(200, login(EMAIL, NEW).statusCode());
    }

    @Test
    void aRejectedPasswordKeepsTheLinkUsable() throws Exception {
        request(EMAIL);
        String token = nextToken(EMAIL);
        HttpResponse<String> weak = confirm(token, "short");
        assertEquals(422, weak.statusCode(), weak.body());
        assertEquals(204, confirm(token, NEW).statusCode());
    }

    @Test
    void expiredRevokedAndUnknownLinksAreRefusedAlike() throws Exception {
        request(EMAIL);
        String first = nextToken(EMAIL);
        request(EMAIL);
        String second = nextToken(EMAIL);
        assertEquals(400, confirm(first, NEW).statusCode(), "a newer request revokes the earlier link");
        jdbc.update("UPDATE password_resets SET created_at = created_at - interval '1 hour', expires_at = now() - interval '1 second' "
                + "WHERE consumed_at IS NULL AND revoked_at IS NULL");
        assertEquals(400, confirm(second, NEW).statusCode(), "expired");
        assertEquals(400, confirm("x".repeat(43), NEW).statusCode(), "unknown");
        assertEquals(400, confirm("x".repeat(129), NEW).statusCode(), "too long");
        assertEquals(200, login(EMAIL, OLD).statusCode());
    }

    @Test
    void aDisabledAccountCannotUseAnEarlierLink() throws Exception {
        request(EMAIL);
        String token = nextToken(EMAIL);
        accounts.disable(studentId);
        assertEquals(400, confirm(token, NEW).statusCode());
    }

    @Test
    void requestsAreLimitedPerAccountPerHour(CapturedOutput output) throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(202, request(EMAIL).statusCode());
            nextToken(EMAIL);
        }
        assertEquals(202, request(EMAIL).statusCode());
        assertNoMail();
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM password_resets", Integer.class));
        assertTrue(output.getAll().contains("over the hourly limit for account " + studentId));
        assertFalse(output.getAll().contains(EMAIL), "the address is never logged");
    }

    @Test
    void aSecondFactorIsStillRequiredForAdministratorsAfterAReset() throws Exception {
        UUID admin = accounts.register(academyId, "admin@example.invalid", Set.of(Role.ACADEMY_ADMIN)).id();
        accounts.setPassword(admin, OLD);
        request("admin@example.invalid");
        assertEquals(204, confirm(nextToken("admin@example.invalid"), NEW).statusCode());
        HttpResponse<String> response = login("admin@example.invalid", NEW);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(true, HttpApi.parse(response).get("mfaRequired"));
        assertFalse(HttpApi.parse(response).containsKey("accessToken"));
    }

    @Test
    void concurrentConfirmationsSucceedExactlyOnce() throws Exception {
        request(EMAIL);
        String token = nextToken(EMAIL);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String password = NEW + " " + i;
                tasks.add(() -> {
                    start.await();
                    return confirm(token, password).statusCode();
                });
            }
            List<Integer> codes = new ArrayList<>();
            for (var future : pool.invokeAll(tasks)) {
                codes.add(future.get());
            }
            assertEquals(1, codes.stream().filter(c -> c == 204).count(), codes.toString());
            assertEquals(threads - 1, codes.stream().filter(c -> c == 400).count(), codes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PASSWORD_RESET_COMPLETED'", Integer.class));
    }
}
