package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** The default configuration: no SMTP server, so self-service reset is switched off. */
class PasswordResetDisabledIntegrationTest extends PostgresIntegrationTest {
    @Value("${local.server.port}")
    int port;

    HttpApi api;

    @BeforeEach
    void setUp() {
        api = new HttpApi(port);
        resetDatabase();
    }

    @Test
    void requestsAreRefusedWithAnExplanationAndNoAccountProbing() {
        HttpResponse<String> response = api.post("/api/v1/auth/password-reset/request",
                HttpApi.json(Map.of("email", "anyone@example.invalid")));
        assertEquals(503, response.statusCode());
        assertEquals("Password reset by email is not available. Ask an academy administrator for help.",
                HttpApi.parse(response).get("detail"));
        assertEquals(400, api.post("/api/v1/auth/password-reset/confirm",
                HttpApi.json(Map.of("token", "x".repeat(43), "password", "bright lantern over harbour"))).statusCode());
    }
}
