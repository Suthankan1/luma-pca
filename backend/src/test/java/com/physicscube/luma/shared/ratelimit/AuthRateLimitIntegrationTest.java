package com.physicscube.luma.shared.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Small limits over real HTTP. The test client connects from this machine, a trusted proxy
 * by default, so X-Forwarded-For stands in for different visitors.
 */
@TestPropertySource(properties = {
    "luma.rate-limit.enabled=true",
    "luma.rate-limit.sign-in.limit=3",
    "luma.rate-limit.link-redemption.limit=3",
    "luma.rate-limit.reset-request.limit=2"})
@ExtendWith(OutputCaptureExtension.class)
class AuthRateLimitIntegrationTest extends PostgresIntegrationTest {
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String WRONG = "{\"email\":\"nobody@example.invalid\",\"password\":\"not the passphrase\"}";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RateLimitCounters counters;
    @Value("${local.server.port}")
    int port;

    HttpApi api;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port);
        resetDatabase();
        jdbc.update("DELETE FROM rate_limit_counters");
    }

    @Test
    void aClientIsRefusedAfterItsLimitWhileOthersCarryOn(CapturedOutput output) {
        for (int i = 0; i < 3; i++) {
            assertEquals(401, api.postFrom("203.0.113.10", LOGIN, WRONG).statusCode());
        }
        HttpResponse<String> refused = api.postFrom("203.0.113.10", LOGIN, WRONG);
        assertEquals(429, refused.statusCode());
        long retryAfter = Long.parseLong(refused.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter >= 1 && retryAfter <= 300, "within the 5-minute window: " + retryAfter);
        assertEquals("application/problem+json", refused.headers().firstValue("Content-Type").orElse("").split(";")[0]);
        assertTrue(((String) HttpApi.parse(refused).get("detail")).startsWith("Too many attempts from this network. Try again in "));

        assertEquals(401, api.postFrom("203.0.113.11", LOGIN, WRONG).statusCode(), "another client is unaffected");
        assertTrue(output.getAll().contains("Rate limit reached for sign-in"));
        assertFalse(output.getAll().contains("203.0.113.10"), "the address is not logged");
    }

    @Test
    void routesAreCountedSeparatelyAndOnlyPublicAuthRoutesAreLimited() {
        for (int i = 0; i < 3; i++) {
            api.postFrom("203.0.113.20", LOGIN, WRONG);
        }
        assertEquals(429, api.postFrom("203.0.113.20", LOGIN, WRONG).statusCode());
        // Other buckets have their own counts.
        assertEquals(400, api.postFrom("203.0.113.20", "/api/v1/auth/activate",
                "{\"token\":\"" + "x".repeat(43) + "\",\"password\":\"bright lantern over harbour\"}").statusCode());
        // Refresh and logout carry their own credential and are not limited here.
        for (int i = 0; i < 6; i++) {
            assertNotEquals(429, api.postFrom("203.0.113.20", "/api/v1/auth/refresh", "{\"refreshToken\":\"unknown\"}").statusCode());
        }
    }

    @Test
    void resetRequestsAreLimitedWithoutRevealingAnything() {
        String body = "{\"email\":\"anyone@example.invalid\"}";
        // Email is off in this context: requests answer 503, and still count.
        assertEquals(503, api.postFrom("203.0.113.30", "/api/v1/auth/password-reset/request", body).statusCode());
        assertEquals(503, api.postFrom("203.0.113.30", "/api/v1/auth/password-reset/request", body).statusCode());
        assertEquals(429, api.postFrom("203.0.113.30", "/api/v1/auth/password-reset/request", body).statusCode());
    }

    @Test
    void ipv6ClientsAreCountedPerSlash64() {
        for (int i = 1; i <= 3; i++) {
            api.postFrom("2001:db8:1:2::" + i, LOGIN, WRONG);
        }
        assertEquals(429, api.postFrom("2001:db8:1:2:ffff::9", LOGIN, WRONG).statusCode(), "same /64, new address");
        assertEquals(401, api.postFrom("2001:db8:1:3::1", LOGIN, WRONG).statusCode(), "a different /64");
    }

    @Test
    void concurrentAttemptsNeverExceedTheLimit() throws Exception {
        int threads = 10;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    start.await();
                    return api.postFrom("203.0.113.40", LOGIN, WRONG).statusCode();
                });
            }
            List<Integer> codes = new ArrayList<>();
            for (var future : pool.invokeAll(tasks)) {
                codes.add(future.get());
            }
            assertEquals(3, codes.stream().filter(c -> c == 401).count(), codes.toString());
            assertEquals(7, codes.stream().filter(c -> c == 429).count(), codes.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oldWindowsAreDeleted() {
        api.postFrom("203.0.113.50", LOGIN, WRONG);
        jdbc.update("INSERT INTO rate_limit_counters (bucket, client, window_start, hits) VALUES ('sign-in', '203.0.113.51', ?, 5)",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3 * 86400)));
        assertEquals(1, counters.deleteOlderThan(Instant.now().minusSeconds(86400)));
        assertEquals(Map.of("client", "203.0.113.50"), jdbc.queryForMap("SELECT client FROM rate_limit_counters"));
    }
}
