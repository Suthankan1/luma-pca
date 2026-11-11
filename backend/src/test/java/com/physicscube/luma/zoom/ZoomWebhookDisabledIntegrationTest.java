package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Without a configured secret the endpoint does not exist, even for correctly signed requests. */
class ZoomWebhookDisabledIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    @Test
    void ingressIsOffByDefault() throws Exception {
        // Other test classes share the database; start from a known state.
        resetDatabase();
        byte[] body = ZoomWebhookHttpIntegrationTest.joined(1, "X").getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/zoom/webhooks"))
                .header("Content-Type", "application/json")
                .header("x-zm-request-timestamp", Long.toString(now))
                .header("x-zm-signature", ZoomWebhookHttpIntegrationTest.sign(ZoomWebhookSignatureTest.FIXTURE_KEY, now, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zoom_webhook_receipts", Integer.class));
    }
}
