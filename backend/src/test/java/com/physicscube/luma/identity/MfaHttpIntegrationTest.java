package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class MfaHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSPHRASE = "quiet river under bridge";
    private static final String ADMIN = "admin@example.invalid";

    @Autowired
    AccountService accounts;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    HttpApi api;
    UUID academyId;
    UUID adminId;

    @BeforeEach
    void seed() {
        api = new HttpApi(port);
        resetDatabase();
        academyId = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Test Academy')", academyId);
        adminId = register(ADMIN, Role.ACADEMY_ADMIN);
    }

    private UUID register(String email, Role... roles) {
        UUID id = accounts.register(academyId, email, Set.of(roles)).id();
        accounts.setPassword(id, PASSPHRASE);
        return id;
    }

    private HttpResponse<String> login(String email, String passphrase) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", passphrase);
        return api.post("/api/v1/auth/login", HttpApi.json(body));
    }

    /** Password step for an account that needs MFA; returns the challenge token. */
    private String challenge(String email) {
        HttpResponse<String> response = login(email, PASSPHRASE);
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> body = HttpApi.parse(response);
        assertEquals(true, body.get("mfaRequired"), response.body());
        return (String) body.get("mfaToken");
    }

    private HttpResponse<String> enroll(String challenge) {
        return api.post("/api/v1/auth/mfa/enroll", HttpApi.json(Map.of("mfaToken", challenge)));
    }

    private HttpResponse<String> verify(String challenge, String code) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("mfaToken", challenge);
        body.put("code", code);
        return api.post("/api/v1/auth/mfa/verify", HttpApi.json(body));
    }

    private static String code(byte[] secret, int stepOffset) {
        return Totp.code(secret, Totp.step(Instant.now()) + stepOffset);
    }

    /** A code that is certainly wrong for every step the server would accept. */
    private static String wrongCode(byte[] secret) {
        long current = Totp.step(Instant.now());
        Set<String> valid = new HashSet<>();
        for (int offset = -2; offset <= 2; offset++) {
            valid.add(Totp.code(secret, current + offset));
        }
        return IntStream.range(0, 10).mapToObj(i -> "00000" + i).filter(c -> !valid.contains(c)).findFirst().orElseThrow();
    }

    /** Enrols the admin and returns the authenticator secret; leaves one MFA session behind. */
    private byte[] enrolledSecret() {
        String challenge = challenge(ADMIN);
        byte[] secret = Base32.decode((String) HttpApi.parse(enroll(challenge)).get("secret"));
        assertEquals(200, verify(challenge, code(secret, 0)).statusCode());
        // Allow the tests that follow to use the current step again.
        jdbc.update("UPDATE account_totp SET last_used_step = NULL WHERE account_id = ?", adminId);
        return secret;
    }

    private int me(String accessToken) {
        return api.get("/api/v1/auth/me", accessToken).statusCode();
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstAdminSignInEnrolsAnAuthenticatorAndReturnsRecoveryCodesOnce() {
        HttpResponse<String> passwordStep = login(ADMIN, PASSPHRASE);
        Map<String, Object> challenge = HttpApi.parse(passwordStep);
        assertEquals(false, challenge.get("mfaEnrolled"));
        assertEquals(300, ((Number) challenge.get("mfaExpiresIn")).intValue());
        assertFalse(passwordStep.body().contains("accessToken"));
        String token = (String) challenge.get("mfaToken");

        HttpResponse<String> enrolment = enroll(token);
        assertEquals(200, enrolment.statusCode(), enrolment.body());
        String secret = (String) HttpApi.parse(enrolment).get("secret");
        assertTrue(secret.matches("[A-Z2-7]{32}"), "160-bit Base32 secret expected");
        assertEquals("otpauth://totp/LUMA%3Aadmin%40example.invalid?secret=" + secret
                + "&issuer=LUMA&algorithm=SHA1&digits=6&period=30", HttpApi.parse(enrolment).get("otpauthUri"));
        assertEquals(401, me(token));

        HttpResponse<String> verified = verify(token, code(Base32.decode(secret), 0));

        assertEquals(200, verified.statusCode(), verified.body());
        Map<String, Object> body = HttpApi.parse(verified);
        List<String> recoveryCodes = (List<String>) body.get("recoveryCodes");
        assertEquals(10, recoveryCodes.size());
        assertEquals(10, Set.copyOf(recoveryCodes).size());
        assertTrue(recoveryCodes.stream().allMatch(c -> c.matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}")));
        assertEquals(200, me((String) body.get("accessToken")));
        assertEquals(List.of("ACADEMY_ADMIN"),
                HttpApi.parse(api.get("/api/v1/auth/me", (String) body.get("accessToken"))).get("roles"));
        assertEquals(true, jdbc.queryForObject("SELECT mfa_verified FROM refresh_sessions", Boolean.class));
        assertNotNull(jdbc.queryForObject("SELECT confirmed_at FROM account_totp WHERE account_id = ?", Object.class, adminId));
    }

    @Test
    void secretsAndRecoveryCodesAreNotStoredInReadableForm() {
        String challenge = challenge(ADMIN);
        String secret = (String) HttpApi.parse(enroll(challenge)).get("secret");
        HttpResponse<String> verified = verify(challenge, code(Base32.decode(secret), 0));
        String firstRecoveryCode = ((List<?>) HttpApi.parse(verified).get("recoveryCodes")).get(0).toString();

        byte[] stored = jdbc.queryForObject("SELECT secret_ciphertext FROM account_totp WHERE account_id = ?", byte[].class, adminId);
        String storedText = new String(stored, StandardCharsets.ISO_8859_1);
        assertEquals(12 + 20 + 16, stored.length);
        assertFalse(storedText.contains(secret));
        assertFalse(storedText.contains(new String(Base32.decode(secret), StandardCharsets.ISO_8859_1)));
        List<String> hashes = jdbc.queryForList("SELECT code_hash FROM account_recovery_codes", String.class);
        assertEquals(10, hashes.size());
        assertTrue(hashes.stream().allMatch(hash -> hash.matches("[0-9a-f]{64}")));
        assertFalse(hashes.contains(firstRecoveryCode));
        assertTrue(jdbc.queryForObject("SELECT token_hash FROM mfa_challenges", String.class).matches("[0-9a-f]{64}"));
    }

    @Test
    void laterSignInsNeedACodeAndCannotReEnrol() {
        byte[] secret = enrolledSecret();
        HttpResponse<String> passwordStep = login(ADMIN, PASSPHRASE);
        assertEquals(true, HttpApi.parse(passwordStep).get("mfaEnrolled"));
        String challenge = (String) HttpApi.parse(passwordStep).get("mfaToken");

        assertEquals(401, enroll(challenge).statusCode(), "an enrolled authenticator cannot be replaced from the sign-in flow");
        HttpResponse<String> verified = verify(challenge, code(secret, 0));

        assertEquals(200, verified.statusCode(), verified.body());
        assertNull(HttpApi.parse(verified).get("recoveryCodes"), "recovery codes are shown only at enrolment");
        assertEquals(200, me((String) HttpApi.parse(verified).get("accessToken")));
    }

    @Test
    void aCodeAndAChallengeAreEachSingleUse() {
        byte[] secret = enrolledSecret();
        String first = challenge(ADMIN);
        String current = code(secret, 0);
        assertEquals(200, verify(first, current).statusCode());

        assertEquals(401, verify(challenge(ADMIN), current).statusCode(), "the same code cannot be replayed");
        assertEquals(401, verify(first, code(secret, 1)).statusCode(), "a used challenge is dead even with a fresh code");
        assertEquals(200, verify(challenge(ADMIN), code(secret, 1)).statusCode(), "the next code still works");
    }

    @Test
    void allVerificationFailuresLookIdentical() {
        byte[] secret = enrolledSecret();
        String expired = challenge(ADMIN);
        jdbc.update("UPDATE mfa_challenges SET created_at = NOW() - INTERVAL '10 minutes', expires_at = NOW() - INTERVAL '1 second'");

        List<HttpResponse<String>> failures = List.of(verify("A".repeat(43), code(secret, 0)),
                verify(expired, code(secret, 0)), verify(challenge(ADMIN), wrongCode(secret)),
                verify(challenge(ADMIN), "ABCD-EFGH-IJKL-MNOP"), verify(challenge(ADMIN), "not a code"),
                enroll("A".repeat(43)), enroll(expired));

        for (HttpResponse<String> failure : failures) {
            assertEquals(401, failure.statusCode());
            assertEquals(failures.get(0).body().replaceAll("/mfa/\\w+", ""), failure.body().replaceAll("/mfa/\\w+", ""));
            assertFalse(failure.body().contains("accessToken"));
        }
        assertEquals(400, api.post("/api/v1/auth/mfa/verify", "{}").statusCode());
        assertEquals(400, verify(challenge(ADMIN), "1".repeat(33)).statusCode());
    }

    @Test
    void fiveWrongCodesLockTheAccountEvenForTheRightCodeAndPassword() {
        byte[] secret = enrolledSecret();
        String wrong = wrongCode(secret);
        for (int i = 0; i < 4; i++) {
            assertEquals(401, verify(challenge(ADMIN), wrong).statusCode());
        }
        assertNull(jdbc.queryForObject("SELECT locked_until FROM accounts WHERE id = ?", Object.class, adminId));
        String open = challenge(ADMIN);

        assertEquals(401, verify(open, wrong).statusCode());

        assertNotNull(jdbc.queryForObject("SELECT locked_until FROM accounts WHERE id = ?", Object.class, adminId));
        assertEquals(401, verify(open, code(secret, 0)).statusCode(), "right code, locked account");
        assertEquals(401, login(ADMIN, PASSPHRASE).statusCode(), "right password, locked account");

        jdbc.update("UPDATE accounts SET locked_until = NOW() - INTERVAL '1 second' WHERE id = ?", adminId);
        assertEquals(200, verify(challenge(ADMIN), code(secret, 0)).statusCode(), "works again once the lock expires");
        assertEquals(0, jdbc.queryForObject("SELECT failed_count FROM account_totp WHERE account_id = ?", Integer.class, adminId));
    }

    @Test
    void aCorrectCodeResetsTheFailureCount() {
        byte[] secret = enrolledSecret();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                assertEquals(401, verify(challenge(ADMIN), wrongCode(secret)).statusCode());
            }
            jdbc.update("UPDATE account_totp SET last_used_step = NULL WHERE account_id = ?", adminId);
            assertEquals(200, verify(challenge(ADMIN), code(secret, 0)).statusCode());
        }
        assertNull(jdbc.queryForObject("SELECT locked_until FROM accounts WHERE id = ?", Object.class, adminId));
    }

    @Test
    void parallelGuessesCannotExceedTheFailureLimit() throws Exception {
        byte[] secret = enrolledSecret();
        String wrong = wrongCode(secret);
        int guesses = 16;
        List<String> challenges = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            challenges.add(challenge(ADMIN));
        }
        CyclicBarrier start = new CyclicBarrier(guesses);
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        try {
            for (Future<Integer> result : pool.invokeAll(challenges.stream().<Callable<Integer>>map(challenge -> () -> {
                start.await();
                return verify(challenge, wrong).statusCode();
            }).toList())) {
                assertEquals(401, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertNotNull(jdbc.queryForObject("SELECT locked_until FROM accounts WHERE id = ?", Object.class, adminId));
        assertEquals(0, jdbc.queryForObject("SELECT failed_count FROM account_totp WHERE account_id = ?", Integer.class, adminId),
                "exactly five guesses were evaluated before the lock; the other eleven were refused unevaluated");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions", Integer.class), "only the enrolment session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecoveryCodeWorksExactlyOnce() {
        String challenge = challenge(ADMIN);
        byte[] secret = Base32.decode((String) HttpApi.parse(enroll(challenge)).get("secret"));
        List<String> recoveryCodes = (List<String>) HttpApi.parse(verify(challenge, code(secret, 0))).get("recoveryCodes");
        String recovery = recoveryCodes.get(3);

        HttpResponse<String> first = verify(challenge(ADMIN), recovery.toLowerCase().replace("-", " "));

        assertEquals(200, first.statusCode(), first.body());
        assertEquals(200, me((String) HttpApi.parse(first).get("accessToken")));
        assertEquals(401, verify(challenge(ADMIN), recovery).statusCode());
        assertEquals(200, verify(challenge(ADMIN), recoveryCodes.get(4)).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM account_recovery_codes WHERE used_at IS NOT NULL", Integer.class));
    }

    @Test
    void recoveryCodesDoNotWorkBeforeEnrolmentIsConfirmed() {
        String challenge = challenge(ADMIN);
        enroll(challenge);

        assertEquals(401, verify(challenge, "ABCD-EFGH-IJKL-MNOP").statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions", Integer.class));
    }

    @Test
    void verifyingWithoutEnrollingFirstFails() {
        assertEquals(401, verify(challenge(ADMIN), "123456").statusCode());
    }

    @Test
    void studentsSignInWithoutMfaAndCannotUseTheMfaRoutes() {
        register("student@example.invalid", Role.STUDENT);

        HttpResponse<String> response = login("student@example.invalid", PASSPHRASE);

        Map<String, Object> body = HttpApi.parse(response);
        assertFalse(body.containsKey("mfaRequired"));
        String access = (String) body.get("accessToken");
        assertEquals(200, me(access));
        assertEquals(false, jdbc.queryForObject("SELECT mfa_verified FROM refresh_sessions", Boolean.class));
        assertEquals(400, enroll(access).statusCode(), "an access token is too long to even be a challenge");
        assertEquals(401, enroll((String) body.get("refreshToken")).statusCode());
    }

    @Test
    void anAccountPromotedAfterSignInMustSignInAgainWithMfa() {
        UUID instructorId = register("learner@example.invalid", Role.STUDENT);
        Map<String, Object> tokens = HttpApi.parse(login("learner@example.invalid", PASSPHRASE));
        String access = (String) tokens.get("accessToken");
        assertEquals(200, me(access));

        jdbc.update("INSERT INTO account_roles (account_id, role) VALUES (?, 'ACADEMY_ADMIN')", instructorId);

        assertEquals(401, me(access), "a session without MFA must not carry staff authority");
        assertEquals(401, api.get("/api/v1/courses", access).statusCode());
        assertEquals(401, api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", tokens.get("refreshToken"))))
                .statusCode());
        assertEquals("ACCOUNT_CHANGED", jdbc.queryForObject(
                "SELECT revoke_reason FROM refresh_sessions WHERE account_id = ?", String.class, instructorId));
        assertEquals(true, HttpApi.parse(login("learner@example.invalid", PASSPHRASE)).get("mfaRequired"));
    }

    @Test
    void anAccountThatKeepsItsAuthenticatorStillNeedsItAfterDemotion() {
        byte[] secret = enrolledSecret();
        jdbc.update("UPDATE account_roles SET role = 'INSTRUCTOR' WHERE account_id = ?", adminId);

        HttpResponse<String> passwordStep = login(ADMIN, PASSPHRASE);

        assertEquals(true, HttpApi.parse(passwordStep).get("mfaRequired"));
        assertEquals(200, verify((String) HttpApi.parse(passwordStep).get("mfaToken"), code(secret, 0)).statusCode());
    }

    @Test
    void aPasswordChangeOrDisablementInvalidatesAPendingChallenge() {
        byte[] secret = enrolledSecret();
        String beforeChange = challenge(ADMIN);
        accounts.setPassword(adminId, "a different long passphrase");
        assertEquals(401, verify(beforeChange, code(secret, 0)).statusCode());

        String beforeDisable = (String) HttpApi.parse(login(ADMIN, "a different long passphrase")).get("mfaToken");
        accounts.disable(adminId);
        assertEquals(401, verify(beforeDisable, code(secret, 0)).statusCode());
    }

    @Test
    void enrolmentCanBeRetriedAfterSessionCreationFails() {
        String challenge = challenge(ADMIN);
        byte[] secret = Base32.decode((String) HttpApi.parse(enroll(challenge)).get("secret"));
        jdbc.execute("ALTER TABLE refresh_sessions ADD CONSTRAINT ck_test_session_write_unavailable CHECK (NOT mfa_verified)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> auth.completeMfa(challenge, code(secret, 0)));
        } finally {
            jdbc.execute("ALTER TABLE refresh_sessions DROP CONSTRAINT ck_test_session_write_unavailable");
        }

        assertNull(jdbc.queryForObject("SELECT confirmed_at FROM account_totp WHERE account_id = ?", Object.class, adminId));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM mfa_challenges WHERE account_id = ?", Object.class, adminId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_recovery_codes", Integer.class));
        assertEquals(200, verify(challenge, code(secret, 0)).statusCode());
    }

    @Test
    void resetRequiresThePasswordThenForcesReEnrolmentAndSignsOutEverywhere() {
        byte[] secret = enrolledSecret();
        HttpResponse<String> session = verify(challenge(ADMIN), code(secret, 0));
        Map<String, Object> tokens = HttpApi.parse(session);
        String access = (String) tokens.get("accessToken");
        String refreshBody = HttpApi.json(Map.of("refreshToken", tokens.get("refreshToken")));
        String pendingChallenge = challenge(ADMIN);
        String resetBody = HttpApi.json(Map.of("password", PASSPHRASE));

        assertEquals(401, api.post("/api/v1/auth/mfa/reset", resetBody).statusCode(), "needs a signed-in session");
        assertEquals(401, api.post("/api/v1/auth/mfa/reset", HttpApi.json(Map.of("password", "not the right passphrase")), access).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account_totp", Integer.class));
        assertEquals(200, me(access), "a failed reset changes nothing");

        assertEquals(204, api.post("/api/v1/auth/mfa/reset", resetBody, access).statusCode());

        assertEquals(401, me(access));
        assertEquals(401, api.post("/api/v1/auth/refresh", refreshBody).statusCode());
        assertEquals(401, enroll(pendingChallenge).statusCode(), "reset also clears pending challenges");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE revoked_at IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_totp", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_recovery_codes", Integer.class));
        Map<String, Object> next = HttpApi.parse(login(ADMIN, PASSPHRASE));
        assertEquals(false, next.get("mfaEnrolled"));
        String newSecret = (String) HttpApi.parse(enroll((String) next.get("mfaToken"))).get("secret");
        assertNotEquals(Base32.encode(secret), newSecret);
    }

    @Test
    void reEnrollingBeforeConfirmationReplacesTheSecret() {
        String challenge = challenge(ADMIN);
        byte[] first = Base32.decode((String) HttpApi.parse(enroll(challenge)).get("secret"));
        byte[] second = Base32.decode((String) HttpApi.parse(enroll(challenge)).get("secret"));

        assertFalse(java.util.Arrays.equals(first, second));
        assertEquals(401, verify(challenge, code(first, 0)).statusCode(), "the abandoned secret no longer works");
        assertEquals(200, verify(challenge(ADMIN), code(second, 0)).statusCode());
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO account_totp (account_id, secret_ciphertext, created_at, failed_count) VALUES (?, ?, NOW(), -1)",
                adminId, new byte[48]));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO account_recovery_codes (id, account_id, code_hash, created_at) VALUES (?, ?, 'PLAIN-CODE', NOW())",
                UUID.randomUUID(), adminId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO mfa_challenges (id, account_id, token_hash, auth_version, created_at, expires_at) "
                        + "VALUES (?, ?, ?, 1, NOW(), NOW() - INTERVAL '1 minute')", UUID.randomUUID(), adminId, "ab".repeat(32)));
        enrolledSecret();
        jdbc.update("DELETE FROM accounts WHERE id = ?", adminId);
        assertEquals(0, jdbc.queryForObject("SELECT (SELECT count(*) FROM account_totp) + (SELECT count(*) FROM "
                + "account_recovery_codes) + (SELECT count(*) FROM mfa_challenges)", Integer.class));
    }
}
