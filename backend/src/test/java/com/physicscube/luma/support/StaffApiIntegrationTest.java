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

/** Base for tests that call staff routes over real HTTP, signing in the way real staff do. */
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

    /** Redeems an invitation over HTTP with the test passphrase; returns the HTTP status. */
    protected int activateWithInvitation(String token) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("password", PASSPHRASE);
        return api.post("/api/v1/auth/activate", HttpApi.json(body)).statusCode();
    }

    /** Password sign-in over HTTP; returns the raw response. */
    protected HttpResponse<String> passwordLogin(String email) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSPHRASE);
        return api.post("/api/v1/auth/login", HttpApi.json(body));
    }

    /** Activates a pending account (for example one created with a student record) with the test passphrase. */
    protected void activate(UUID accountId) {
        accounts.setPassword(accountId, PASSPHRASE);
    }

    /** Signs in with email and password and returns an access token. */
    protected String login(String email) {
        return (String) loginSession(email).get("accessToken");
    }

    /** As {@link #login}, returning the whole session response (access and refresh tokens). */
    protected Map<String, Object> loginSession(String email) {
        HttpResponse<String> response = passwordLogin(email);
        assertEquals(200, response.statusCode(), response.body());
        return HttpApi.parse(response);
    }
}
