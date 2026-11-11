package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;

class RefreshSessionHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSPHRASE = "quiet river under bridge";
    private static final String EMAIL = "student@example.invalid";

    @Autowired
    AccountService accounts;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    HttpApi api;
    UUID academyId;
    UUID studentId;

    record Pair(String access, String refresh, long refreshExpiresIn) {
    }

    @BeforeEach
    void seed() {
        api = new HttpApi(port);
        resetDatabase();
        academyId = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Test Academy')", academyId);
        studentId = accounts.register(academyId, EMAIL, Set.of(Role.STUDENT)).id();
        accounts.setPassword(studentId, PASSPHRASE);
    }

    private static Pair pair(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> body = HttpApi.parse(response);
        return new Pair((String) body.get("accessToken"), (String) body.get("refreshToken"),
                ((Number) body.get("refreshExpiresIn")).longValue());
    }

    private Pair login(String email) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSPHRASE);
        return pair(api.post("/api/v1/auth/login", HttpApi.json(body)));
    }

    private HttpResponse<String> refresh(String refreshToken) {
        return api.post("/api/v1/auth/refresh", HttpApi.json(Map.of("refreshToken", refreshToken)));
    }

    private int logout(String refreshToken) {
        return api.post("/api/v1/auth/logout", HttpApi.json(Map.of("refreshToken", refreshToken))).statusCode();
    }

    private int me(String accessToken) {
        return api.get("/api/v1/auth/me", accessToken).statusCode();
    }

    private String sessionId(String accessToken) {
        return decoder.decode(accessToken).getClaimAsString("sid");
    }

    private String revokeReason(String sessionId) {
        return jdbc.queryForObject("SELECT revoke_reason FROM refresh_sessions WHERE id = ?::uuid", String.class, sessionId);
    }

    @Test
    void loginStartsASessionAndStoresOnlyTheTokenDigest() {
        Pair issued = login(EMAIL);

        assertTrue(issued.refresh().matches("[A-Za-z0-9_-]{43}"), "256-bit base64url token expected");
        long sevenDays = Duration.ofDays(7).toSeconds();
        assertTrue(issued.refreshExpiresIn() > sevenDays - 60 && issued.refreshExpiresIn() <= sevenDays);
        Map<String, Object> session = jdbc.queryForMap("SELECT * FROM refresh_sessions WHERE account_id = ?", studentId);
        assertEquals(session.get("id").toString(), sessionId(issued.access()));
        assertEquals(1, session.get("auth_version"));
        String stored = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens WHERE session_id = ?",
                String.class, session.get("id"));
        assertTrue(stored.matches("[0-9a-f]{64}"));
        assertNotEquals(issued.refresh(), stored);
    }

    @Test
    void refreshRotatesBothTokensWithinTheSameSession() {
        Pair first = login(EMAIL);

        Pair second = pair(refresh(first.refresh()));
        Pair third = pair(refresh(second.refresh()));

        assertNotEquals(first.refresh(), second.refresh());
        assertNotEquals(second.refresh(), third.refresh());
        assertNotEquals(first.access(), third.access());
        assertEquals(sessionId(first.access()), sessionId(third.access()));
        assertEquals(200, me(third.access()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE used_at IS NULL", Integer.class));
    }

    @Test
    void reusingAnExchangedTokenRevokesTheWholeSession() {
        Pair first = login(EMAIL);
        Pair second = pair(refresh(first.refresh()));
        Pair otherDevice = login(EMAIL);

        HttpResponse<String> replay = refresh(first.refresh());

        assertEquals(401, replay.statusCode());
        assertFalse(replay.body().contains("accessToken"));
        assertEquals("REUSE_DETECTED", revokeReason(sessionId(first.access())));
        assertEquals(401, refresh(second.refresh()).statusCode(), "the legitimate holder's newer token must die too");
        assertEquals(401, me(second.access()), "access tokens of the revoked session must stop working");
        assertEquals(401, me(first.access()));
        assertEquals(200, me(otherDevice.access()), "a separate sign-in is not affected");
        assertEquals(200, refresh(otherDevice.refresh()).statusCode());
    }

    @Test
    void concurrentRefreshesOfOneTokenYieldOneWinnerThenRevokeTheSession() throws Exception {
        Pair issued = login(EMAIL);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<HttpResponse<String>> responses;
        try {
            List<Future<HttpResponse<String>>> futures = pool.invokeAll(IntStream.range(0, threads)
                    .<Callable<HttpResponse<String>>>mapToObj(i -> () -> {
                        start.await();
                        return refresh(issued.refresh());
                    }).toList());
            responses = new java.util.ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        List<HttpResponse<String>> winners = responses.stream().filter(r -> r.statusCode() == 200).toList();
        assertEquals(1, winners.size());
        assertEquals(threads - 1, responses.stream().filter(r -> r.statusCode() == 401).count());
        assertEquals("REUSE_DETECTED", revokeReason(sessionId(issued.access())));
        assertEquals(401, refresh(pair(winners.get(0)).refresh()).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class));
    }

    @Test
    void allRefreshFailuresLookIdentical() {
        Pair used = login(EMAIL);
        refresh(used.refresh());
        Pair expired = login(EMAIL);
        jdbc.update("UPDATE refresh_tokens SET created_at = NOW() - INTERVAL '8 days', "
                + "expires_at = NOW() - INTERVAL '1 second' WHERE used_at IS NULL AND session_id = ?::uuid", sessionId(expired.access()));
        Pair loggedOut = login(EMAIL);
        logout(loggedOut.refresh());

        List<HttpResponse<String>> failures = List.of(refresh("A".repeat(43)), refresh("x"), refresh(used.refresh()),
                refresh(expired.refresh()), refresh(loggedOut.refresh()));

        for (HttpResponse<String> failure : failures) {
            assertEquals(401, failure.statusCode());
            assertEquals(failures.get(0).body(), failure.body());
        }
        assertEquals(400, api.post("/api/v1/auth/refresh", "{}").statusCode());
        assertEquals(400, refresh("x".repeat(129)).statusCode());
        assertEquals(400, api.post("/api/v1/auth/refresh", "not json").statusCode());
    }

    @Test
    void expiredTokenIsRejectedWithoutBeingTreatedAsTheft() {
        Pair issued = login(EMAIL);
        jdbc.update("UPDATE refresh_tokens SET created_at = NOW() - INTERVAL '8 days', expires_at = NOW() - INTERVAL '1 second'");

        assertEquals(401, refresh(issued.refresh()).statusCode());
        assertEquals(401, refresh(issued.refresh()).statusCode());

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE revoked_at IS NOT NULL", Integer.class));
    }

    @Test
    void refreshingNeverExtendsASessionBeyondItsAbsoluteLimit() {
        Pair issued = login(EMAIL);
        jdbc.update("UPDATE refresh_sessions SET created_at = NOW() - INTERVAL '29 days', expires_at = NOW() + INTERVAL '1 hour'");

        Pair rotated = pair(refresh(issued.refresh()));

        assertTrue(rotated.refreshExpiresIn() <= 3600 && rotated.refreshExpiresIn() > 3500,
                "new token must expire with the session, got " + rotated.refreshExpiresIn());

        jdbc.update("UPDATE refresh_sessions SET expires_at = NOW() - INTERVAL '1 second'");
        assertEquals(401, refresh(rotated.refresh()).statusCode());
        assertEquals(401, me(rotated.access()), "access tokens end with the session");
    }

    @Test
    void logoutEndsOnlyThatSessionAndIsIdempotent() {
        Pair laptop = login(EMAIL);
        Pair phone = login(EMAIL);
        assertEquals(200, me(laptop.access()));

        assertEquals(204, logout(laptop.refresh()));

        assertEquals("LOGOUT", revokeReason(sessionId(laptop.access())));
        assertEquals(401, me(laptop.access()), "logout must end the access token immediately");
        assertEquals(401, refresh(laptop.refresh()).statusCode());
        assertEquals(200, me(phone.access()));
        assertEquals(204, logout(laptop.refresh()));
        assertEquals(204, logout("A".repeat(43)));
        assertEquals(400, api.post("/api/v1/auth/logout", "{}").statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE revoked_at IS NULL", Integer.class));
    }

    @Test
    void logoutWithAnAlreadyExchangedTokenStillEndsTheSession() {
        Pair first = login(EMAIL);
        Pair second = pair(refresh(first.refresh()));

        assertEquals(204, logout(first.refresh()));

        assertEquals(401, refresh(second.refresh()).statusCode());
        assertEquals("LOGOUT", revokeReason(sessionId(first.access())));
    }

    @Test
    void logoutAllEndsEverySessionOfTheCallerOnly() {
        UUID otherId = accounts.register(academyId, "other@example.invalid", Set.of(Role.STUDENT)).id();
        accounts.setPassword(otherId, PASSPHRASE);
        Pair laptop = login(EMAIL);
        Pair phone = login(EMAIL);
        Pair other = login("other@example.invalid");

        assertEquals(401, api.post("/api/v1/auth/logout-all", "").statusCode(), "requires a bearer token");
        HttpResponse<String> response = api.post("/api/v1/auth/logout-all", "", laptop.access());

        assertEquals(204, response.statusCode());
        assertEquals(401, me(laptop.access()));
        assertEquals(401, me(phone.access()));
        assertEquals(401, refresh(phone.refresh()).statusCode());
        assertEquals("LOGOUT_ALL", revokeReason(sessionId(phone.access())));
        assertEquals(200, me(other.access()));
        assertEquals(200, refresh(other.refresh()).statusCode());
        assertEquals(200, me(login(EMAIL).access()), "signing in again still works");
    }

    @Test
    void passwordChangeAndDisablementEndRefreshSessions() {
        Pair beforeChange = login(EMAIL);
        accounts.setPassword(studentId, "a different long passphrase");

        assertEquals(401, refresh(beforeChange.refresh()).statusCode());
        assertEquals("ACCOUNT_CHANGED", revokeReason(sessionId(beforeChange.access())));

        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", EMAIL);
        body.put("password", "a different long passphrase");
        Pair afterChange = pair(api.post("/api/v1/auth/login", HttpApi.json(body)));
        accounts.disable(studentId);

        assertEquals(401, refresh(afterChange.refresh()).statusCode());
        assertEquals(401, me(afterChange.access()));
    }

    @Test
    void databaseConstraintsHoldEvenWhenTheServiceIsBypassed() {
        String session = sessionId(login(EMAIL).access());
        String insertToken = "INSERT INTO refresh_tokens (id, session_id, token_hash, created_at, expires_at) "
                + "VALUES (?, ?::uuid, ?, NOW(), NOW() + INTERVAL '1 hour')";

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insertToken, UUID.randomUUID(), session, "cd".repeat(32)), "second current token");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE refresh_sessions SET revoked_at = NOW() WHERE id = ?::uuid", session), "revocation needs a reason");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE refresh_sessions SET revoked_at = NOW(), revoke_reason = 'BECAUSE' WHERE id = ?::uuid", session));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE refresh_tokens SET token_hash = 'raw-token' WHERE session_id = ?::uuid", session));
        jdbc.update("DELETE FROM accounts WHERE id = ?", studentId);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class), "sessions go with the account");
    }
}
