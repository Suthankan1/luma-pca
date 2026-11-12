package com.physicscube.luma.shared.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * When the caller is not a trusted proxy, its X-Forwarded-For is ignored: forging a new
 * address per request does not escape the limit.
 */
@TestPropertySource(properties = {
    "luma.rate-limit.enabled=true",
    "luma.rate-limit.sign-in.limit=3",
    "server.tomcat.remoteip.internal-proxies=10\\.255\\.255\\.254"})
class UntrustedForwardingIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void setUp() {
        resetDatabase();
        jdbc.update("DELETE FROM rate_limit_counters");
    }

    @Test
    void forgedForwardedAddressesShareTheCallersCount() {
        HttpApi api = new HttpApi(port);
        String wrong = "{\"email\":\"nobody@example.invalid\",\"password\":\"not the passphrase\"}";
        for (int i = 1; i <= 3; i++) {
            assertEquals(401, api.postFrom("198.51.100." + i, "/api/v1/auth/login", wrong).statusCode());
        }
        assertEquals(429, api.postFrom("198.51.100.99", "/api/v1/auth/login", wrong).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rate_limit_counters WHERE client LIKE '198.51.100.%'", Integer.class));
    }
}
