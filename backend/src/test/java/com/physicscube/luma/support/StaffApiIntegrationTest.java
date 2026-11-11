package com.physicscube.luma.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.identity.AccountService;
import com.physicscube.luma.identity.Base32;
import com.physicscube.luma.identity.Totp;
import com.physicscube.luma.identity.domain.Role;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Base for tests that call staff routes over real HTTP, signing in the way real staff do, MFA included. */
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

    /** Password sign-in over HTTP, for accounts without MFA; returns the raw response. */
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

    /**
     * Signs in and returns an access token. For accounts that need MFA this enrols an
     * authenticator on first use, so call it once per such account: a second sign-in
     * inside the same 30 seconds would be refused as a replayed code.
     */
    protected String login(String email) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSPHRASE);
        HttpResponse<String> response = api.post("/api/v1/auth/login", HttpApi.json(body));
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> result = HttpApi.parse(response);
        if (Boolean.TRUE.equals(result.get("mfaRequired"))) {
            String challenge = (String) result.get("mfaToken");
            HttpResponse<String> enrolment = api.post("/api/v1/auth/mfa/enroll", HttpApi.json(Map.of("mfaToken", challenge)));
            assertEquals(200, enrolment.statusCode(), enrolment.body());
            byte[] secret = Base32.decode((String) HttpApi.parse(enrolment).get("secret"));
            Map<String, String> verification = new LinkedHashMap<>();
            verification.put("mfaToken", challenge);
            verification.put("code", Totp.code(secret, Totp.step(Instant.now())));
            response = api.post("/api/v1/auth/mfa/verify", HttpApi.json(verification));
            assertEquals(200, response.statusCode(), response.body());
            result = HttpApi.parse(response);
        }
        return (String) result.get("accessToken");
    }
}
