package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** A test deployment on an account that also runs real classes stores only its own webinar. */
@TestPropertySource(properties = {
    "luma.zoom.webhook.secret-token=" + ZoomWebhookSignatureTest.FIXTURE_KEY,
    "luma.zoom.webhook.academy-id=" + ZoomWebhookAllowListIntegrationTest.ACADEMY,
    "luma.zoom.webhook.webinar-allow-list=812 3456 7890, 99988877766"})
@ExtendWith(OutputCaptureExtension.class)
class ZoomWebhookAllowListIntegrationTest extends PostgresIntegrationTest {
    static final String ACADEMY = "6f2d3c1e-0000-4000-8000-0000000000a1";

    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void seed() {
        resetDatabase();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?::uuid, 'Allow-list Test')", ACADEMY);
    }

    private static String joined(String webinarIdJson, String attendee) {
        return "{\"event\":\"webinar.participant_joined\",\"event_ts\":" + System.currentTimeMillis() + ",\"payload\":{"
                + "\"account_id\":\"EXAMPLE\",\"object\":{\"id\":" + webinarIdJson + ",\"uuid\":\"Occ123==\",\"participant\":{"
                + "\"user_name\":\"" + attendee + "\",\"email\":\"fictional.attendee@example.invalid\"}}}}";
    }

    private int send(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/zoom/webhooks"))
                .header("Content-Type", "application/json").header("x-zm-request-timestamp", Long.toString(timestamp))
                .header("x-zm-signature", ZoomWebhookHttpIntegrationTest.sign(ZoomWebhookSignatureTest.FIXTURE_KEY, timestamp, bytes))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> storedWebinars() {
        return jdbc.queryForList("SELECT payload->'payload'->'object'->>'id' FROM zoom_webhook_receipts ORDER BY received_at", String.class);
    }

    @Test
    void onlyAllowedWebinarsAreStoredAndOthersAreAcknowledgedUnread(CapturedOutput output) {
        assertEquals(200, send(joined("\"81234567890\"", "TEST-ONE")));
        assertEquals(200, send(joined("99988877766", "TEST-TWO")), "a numeric id is accepted too");
        assertEquals(200, send(joined("\"88284191115\"", "REAL-CLASS-ATTENDEE")), "acknowledged so Zoom does not retry");
        assertEquals(200, send("{\"event\":\"webinar.participant_joined\",\"event_ts\":1,\"payload\":{}}"), "no webinar id");

        assertEquals(List.of("81234567890", "99988877766"), storedWebinars());
        assertEquals(2, output.getAll().split("outside the allow-list; not stored", -1).length - 1,
                "the real class's event and the one without a webinar id were dropped");
        assertFalse(output.getAll().contains("REAL-CLASS-ATTENDEE"), "the dropped payload is not logged");
        assertFalse(output.getAll().contains("88284191115"), "nor its webinar id");
    }

    @Test
    void allowListEntriesMustBeWebinarIds() {
        assertThrows(IllegalArgumentException.class, () -> new ZoomWebhookProperties("x".repeat(20), UUID.randomUUID(),
                Duration.ofMinutes(5), 65536, List.of("not-an-id")));
        assertEquals(List.of("81234567890"), new ZoomWebhookProperties("x".repeat(20), UUID.randomUUID(),
                Duration.ofMinutes(5), 65536, List.of(" 812 3456-7890 ", "")).webinarAllowList());
    }
}
