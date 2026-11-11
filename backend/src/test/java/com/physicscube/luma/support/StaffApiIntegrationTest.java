package com.physicscube.luma.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.identity.AccountService;
import com.physicscube.luma.identity.domain.Role;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for tests that call staff routes over real HTTP. Owner and administrator
 * sign-in is blocked until MFA exists, so these tests enable the development-only
 * override; the block itself is covered by AuthHttpIntegrationTest.
 */
@TestPropertySource(properties = "luma.auth.allow-elevated-login-without-mfa=true")
public abstract class StaffApiIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSPHRASE = "quiet river under bridge";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    protected AccountService accounts;
    @Autowired
    protected JdbcTemplate jdbc;
    @Value("${local.server.port}")
    private int port;

    protected HttpApi api;

    @BeforeEach
    void prepareApi() {
        api = new HttpApi(port);
        resetDatabase();
    }

    protected UUID academy(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    /** Registers an active account with the roles and returns an access token for it. */
    protected String tokenFor(UUID academyId, Role... roles) {
        String email = "user" + SEQUENCE.incrementAndGet() + "@example.invalid";
        accountIdOf(email, academyId, roles);
        return login(email);
    }

    protected UUID accountIdOf(String email, UUID academyId, Role... roles) {
        UUID id = accounts.register(academyId, email, Set.of(roles)).id();
        accounts.setPassword(id, PASSPHRASE);
        return id;
    }

    protected String login(String email) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSPHRASE);
        HttpResponse<String> response = api.post("/api/v1/auth/login", HttpApi.json(body));
        assertEquals(200, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("accessToken");
    }
}
