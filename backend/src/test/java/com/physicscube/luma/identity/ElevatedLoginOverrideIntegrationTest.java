package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** The development-only switch that lets owner/admin accounts sign in before MFA exists. */
@TestPropertySource(properties = "luma.auth.allow-elevated-login-without-mfa=true")
class ElevatedLoginOverrideIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSPHRASE = "quiet river under bridge";

    @Autowired
    AccountService accounts;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    @Test
    void adminCanSignInOnlyWhenTheOverrideIsEnabled() {
        HttpApi api = new HttpApi(port);
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM academies");
        UUID academyId = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Test Academy')", academyId);
        accounts.setPassword(accounts.register(academyId, "admin@example.invalid", Set.of(Role.ACADEMY_ADMIN)).id(), PASSPHRASE);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", "admin@example.invalid");
        body.put("password", PASSPHRASE);

        HttpResponse<String> login = api.post("/api/v1/auth/login", HttpApi.json(body));

        assertEquals(200, login.statusCode(), login.body());
        HttpResponse<String> me = api.get("/api/v1/auth/me", (String) HttpApi.parse(login).get("accessToken"));
        assertEquals(List.of("ACADEMY_ADMIN"), HttpApi.parse(me).get("roles"));
    }
}
