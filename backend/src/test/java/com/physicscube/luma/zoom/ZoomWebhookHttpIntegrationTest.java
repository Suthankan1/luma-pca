package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "luma.zoom.webhook.secret-token=" + ZoomWebhookSignatureTest.FIXTURE_KEY,
    "luma.zoom.webhook.academy-id=" + ZoomWebhookHttpIntegrationTest.ACADEMY})
@ExtendWith(OutputCaptureExtension.class)
class ZoomWebhookHttpIntegrationTest extends PostgresIntegrationTest {
    static final String ACADEMY = "6f2d3c1e-0000-4000-8000-000000000001";
    private static final String PATH = "/api/v1/zoom/webhooks";
    private static final String OTHER_FIXTURE_KEY = "another-fixture-key-for-zoom-tests";

    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void seed() {
        resetDatabase();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?::uuid, 'Physics Cube Test')", ACADEMY);
    }

    static String joined(long eventTs, String userName) {
        return "{\"event\":\"webinar.participant_joined\",\"event_ts\":" + eventTs + ",\"payload\":{\"account_id\":\"EXAMPLE\","
                + "\"object\":{\"id\":\"81234567890\",\"uuid\":\"Abc123==\",\"participant\":{\"user_id\":\"16778240\","
                + "\"user_name\":\"" + userName + "\",\"email\":\"fictional.attendee@example.invalid\","
                + "\"join_time\":\"2027-03-01T08:31:00Z\"}}}}";
    }

    private HttpResponse<String> send(String body, String secret, long timestamp, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = sign(secret, timestamp, bytes);
        return post(bytes, Long.toString(timestamp), signature, contentType);
    }

    private HttpResponse<String> send(String body) {
        return send(body, ZoomWebhookSignatureTest.FIXTURE_KEY, Instant.now().getEpochSecond(), "application/json; charset=utf-8");
    }

    static String sign(String secret, long timestamp, byte[] body) {
        byte[] prefix = ("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v0=" + java.util.HexFormat.of().formatHex(mac.doFinal(message));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> post(byte[] body, String timestamp, String signature, String contentType) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (timestamp != null) {
            request.header("x-zm-request-timestamp", timestamp);
        }
        if (signature != null) {
            request.header("x-zm-signature", signature);
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM zoom_webhook_receipts", Integer.class);
    }

    @Test
    void aVerifiedJoinEventIsStoredBeforeItIsAcknowledged() {
        String body = joined(1_795_000_000_123L, "FICTIONAL-ATTENDEE");

        HttpResponse<String> response = send(body);

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> row = jdbc.queryForMap("SELECT academy_id::text AS academy, event_type, event_ts, body_sha256, "
                + "payload->'payload'->'object'->>'uuid' AS occurrence, processing_status FROM zoom_webhook_receipts");
        assertEquals(ACADEMY, row.get("academy"));
        assertEquals("webinar.participant_joined", row.get("event_type"));
        assertEquals(1_795_000_000_123L, row.get("event_ts"));
        assertEquals("Abc123==", row.get("occurrence"), "the payload is stored as queryable JSON");
        assertEquals(ZoomWebhookReceipts.sha256(body.getBytes(StandardCharsets.UTF_8)), row.get("body_sha256"));
        assertEquals("RECEIVED", row.get("processing_status"));
    }

    @Test
    void urlValidationIsAnsweredButNeverStored() {
        String body = "{\"event\":\"endpoint.url_validation\",\"event_ts\":1795000000000,\"payload\":{\"plainToken\":\"zoomUrlValidationFixture\"}}";

        HttpResponse<String> response = send(body);

        assertEquals(200, response.statusCode());
        Map<String, Object> answer = HttpApi.parse(response);
        assertEquals("zoomUrlValidationFixture", answer.get("plainToken"));
        assertEquals(ZoomWebhookSignatureTest.hmacHex(ZoomWebhookSignatureTest.FIXTURE_KEY, "zoomUrlValidationFixture"),
                answer.get("encryptedToken"));
        assertEquals(0, receipts());
    }

    @Test
    void urlValidationCannotBeUsedToForgeASignature() {
        long now = Instant.now().getEpochSecond();
        String forgedBody = joined(1_795_000_000_999L, "FORGED");
        String oracle = "{\"event\":\"endpoint.url_validation\",\"event_ts\":1,\"payload\":{\"plainToken\":\"v0:" + now + ":"
                + forgedBody.replace("\"", "\\\"") + "\"}}";
        byte[] oracleBytes = oracle.getBytes(StandardCharsets.UTF_8);

        assertEquals(401, post(oracleBytes, Long.toString(now), null, "application/json").statusCode(),
                "an unsigned validation request gets no answer");
        assertEquals(400, send(oracle).statusCode(), "even signed, a plainToken shaped like a signing message is refused");
        assertEquals(400, send("{\"event\":\"endpoint.url_validation\",\"payload\":{\"plainToken\":\"x\"}}").statusCode());
        assertEquals(400, send("{\"event\":\"endpoint.url_validation\",\"payload\":{}}").statusCode());
        assertEquals(0, receipts());
    }

    @Test
    void forgedTamperedStaleAndUnsignedDeliveriesAreRejectedAndNotStored() {
        String body = joined(1_795_000_000_123L, "FICTIONAL-ATTENDEE");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String good = sign(ZoomWebhookSignatureTest.FIXTURE_KEY, now, bytes);

        List<HttpResponse<String>> rejected = List.of(
                post(bytes, null, null, "application/json"),
                post(bytes, Long.toString(now), null, "application/json"),
                post(bytes, null, good, "application/json"),
                send(body, OTHER_FIXTURE_KEY, now, "application/json"),
                post(body.replace("FICTIONAL", "INSERTED").getBytes(StandardCharsets.UTF_8), Long.toString(now), good, "application/json"),
                post(bytes, Long.toString(now + 1), good, "application/json"),
                send(body, ZoomWebhookSignatureTest.FIXTURE_KEY, now - 301, "application/json"),
                send(body, ZoomWebhookSignatureTest.FIXTURE_KEY, now + 301, "application/json"),
                post(bytes, Long.toString(now), good.toUpperCase(), "application/json"),
                post(bytes, Long.toString(now), good.replace("v0=", "v1="), "application/json"));
        for (HttpResponse<String> response : rejected) {
            assertEquals(401, response.statusCode());
            assertEquals("", response.body(), "a rejection reveals nothing");
        }
        assertEquals(0, receipts());
    }

    @Test
    void aRedeliveryIsAcknowledgedButStoredOnce() {
        String body = joined(1_795_000_000_123L, "FICTIONAL-ATTENDEE");

        assertEquals(200, send(body).statusCode());
        assertEquals(200, send(body, ZoomWebhookSignatureTest.FIXTURE_KEY, Instant.now().getEpochSecond() - 120, "application/json")
                .statusCode(), "re-signed with another timestamp, same body");
        assertEquals(200, send(joined(1_795_000_000_456L, "FICTIONAL-ATTENDEE")).statusCode(), "a different event");

        assertEquals(2, receipts());
    }

    @Test
    void concurrentDuplicateDeliveriesProduceExactlyOneReceipt() throws Exception {
        String body = joined(1_795_000_000_123L, "FICTIONAL-ATTENDEE");
        int threads = 12;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(IntStream.range(0, threads).<Callable<Integer>>mapToObj(i -> () -> {
                start.await();
                return send(body).statusCode();
            }).toList())) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(statuses.stream().allMatch(s -> s == 200), statuses.toString());
        assertEquals(1, receipts());
    }

    @Test
    void signedButMalformedEventsAreRejectedAndUnusedTypesAreDropped() {
        assertEquals(400, send("not json").statusCode());
        assertEquals(400, send("[1,2,3]").statusCode());
        assertEquals(400, send("{\"payload\":{}}").statusCode(), "no event type");
        assertEquals(400, send("{\"event\":\"webinar.participant_left\",\"payload\":{}}").statusCode(), "no event_ts");
        assertEquals(400, send("{\"event\":\"webinar.participant_left\",\"event_ts\":\"soon\"}").statusCode());
        assertEquals(200, send("{\"event\":\"meeting.created\",\"event_ts\":1795000000000,\"payload\":{}}").statusCode());
        assertEquals(0, receipts());
        assertEquals(200, send("{\"event\":\"webinar.ended\",\"event_ts\":1795000000000,\"payload\":{}}").statusCode());
        assertEquals(1, receipts());
    }

    @Test
    void oversizedAndNonJsonRequestsAreRefusedBeforeVerification() {
        String huge = "{\"event\":\"webinar.ended\",\"event_ts\":1,\"pad\":\"" + "x".repeat(70_000) + "\"}";
        assertEquals(413, send(huge).statusCode());
        assertEquals(415, send(joined(1, "X"), ZoomWebhookSignatureTest.FIXTURE_KEY, Instant.now().getEpochSecond(), "text/plain")
                .statusCode());
        assertEquals(415, post(joined(1, "X").getBytes(StandardCharsets.UTF_8), null, null, null).statusCode());
        assertEquals(0, receipts());
        HttpResponse<String> get;
        try {
            get = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertTrue(get.statusCode() == 401 || get.statusCode() == 403, "GET returned " + get.statusCode());
    }

    @Test
    void aStorageFailureAsksZoomToRetryAndTheRetrySucceeds() {
        String body = joined(1_795_000_000_123L, "FICTIONAL-ATTENDEE");
        resetDatabase();

        assertEquals(503, send(body).statusCode(), "Zoom retries 5xx responses, never 4xx");

        jdbc.update("INSERT INTO academies (id, name) VALUES (?::uuid, 'Physics Cube Test')", ACADEMY);
        assertEquals(200, send(body).statusCode());
        assertEquals(1, receipts());
    }

    @Test
    void logsNeverContainParticipantNamesEmailsOrSignatures(CapturedOutput output) {
        String body = joined(1_795_000_000_123L, "UNIQUE-NAME-7f3a");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        send(body);
        send(body);
        post(bytes, Long.toString(now), sign(OTHER_FIXTURE_KEY, now, bytes), "application/json");

        String logs = output.getAll();
        assertTrue(logs.contains("Zoom webhook webinar.participant_joined stored"), logs);
        assertTrue(logs.contains("Rejected Zoom webhook: signature MISMATCH"));
        assertFalse(logs.contains("UNIQUE-NAME-7f3a"));
        assertFalse(logs.contains("fictional.attendee@example.invalid"));
        assertFalse(logs.contains(ZoomWebhookSignatureTest.FIXTURE_KEY));
        assertFalse(logs.contains(sign(OTHER_FIXTURE_KEY, now, bytes)));
    }
}
