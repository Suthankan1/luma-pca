package com.physicscube.luma.zoom.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a fake Zoom on localhost: requests are checked as Zoom documents them. */
class ZoomApiClientTest {
    private static final String ACCOUNT = "fake-account";
    private static final String CLIENT = "fake-client";
    private static final String CLIENT_VALUE = "fixture-client-value";

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-13T05:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }
    }

    HttpServer server;
    final Map<String, Function<HttpExchange, Reply>> routes = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger tokens = new AtomicInteger();
    final MutableClock clock = new MutableClock();
    ZoomApiClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String target = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            requests.add(target);
            Reply reply = routes.entrySet().stream().filter(r -> target.startsWith(r.getKey())).findFirst()
                    .map(r -> r.getValue().apply(exchange)).orElse(new Reply(404, "{\"code\":3001,\"message\":\"Not found\"}", Map.of()));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        routes.put("POST /oauth/token", exchange -> {
            String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT + ":" + CLIENT_VALUE).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                return new Reply(401, "{\"reason\":\"Invalid client_id or client_secret\",\"error\":\"invalid_client\"}", Map.of());
            }
            return Reply.ok("{\"access_token\":\"access-" + tokens.incrementAndGet() + "\",\"token_type\":\"bearer\","
                    + "\"expires_in\":3600,\"scope\":\"webinar:read:webinar:admin webinar:read:list_registrants:admin\"}");
        });
        ZoomApiProperties properties = new ZoomApiProperties(ACCOUNT, CLIENT, CLIENT_VALUE, URI.create(base + "/oauth/token"),
                URI.create(base + "/v2"), Duration.ofSeconds(5));
        client = new ZoomApiClient(properties, HttpClient.newHttpClient(), clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String bearer(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst("Authorization");
    }

    @Test
    void aTokenIsFetchedWithAccountCredentialsAndReusedUntilAMinuteBeforeItExpires() {
        routes.put("GET /v2/webinars/83834428148?", exchange -> Reply.ok("{\"id\":83834428148,\"topic\":\"t\",\"type\":5}"));
        client.webinar("83834428148");
        client.webinar("83834428148");
        assertEquals(1, tokens.get());
        assertTrue(requests.get(0).equals("POST /oauth/token?grant_type=account_credentials&account_id=" + ACCOUNT), requests.get(0));

        clock.now = clock.now.plusSeconds(3600 - 61);
        client.webinar("83834428148");
        assertEquals(1, tokens.get(), "still valid");
        clock.now = clock.now.plusSeconds(2);
        client.webinar("83834428148");
        assertEquals(2, tokens.get(), "renewed a minute before expiry");
        assertEquals("webinar:read:webinar:admin webinar:read:list_registrants:admin", client.grantedScopes());
    }

    @Test
    void aRejectedTokenIsReplacedOnce() {
        routes.put("GET /v2/webinars/1", exchange -> "Bearer access-1".equals(bearer(exchange))
                ? new Reply(401, "{\"code\":124,\"message\":\"Invalid access token.\"}", Map.of())
                : Reply.ok("{\"id\":1,\"topic\":\"t\",\"type\":5}"));
        assertEquals(1, client.webinar("1").id());
        assertEquals(2, tokens.get());

        routes.put("GET /v2/webinars/2", exchange -> new Reply(401, "{\"code\":124,\"message\":\"Invalid access token.\"}", Map.of()));
        ZoomApiException e = assertThrows(ZoomApiException.class, () -> client.webinar("2"));
        assertEquals(401, e.status());
        // The cached token is rejected, one new token is fetched and also rejected: no further attempts.
        assertEquals(3, tokens.get(), "one retry, not a loop");
    }

    @Test
    void aWebinarAndItsOccurrencesAreRead() {
        routes.put("GET /v2/webinars/88284191115?show_previous_occurrences=true", exchange -> Reply.ok("""
                {"id":88284191115,"uuid":"abc==","topic":"Physics","start_time":"2026-11-12T11:00:00Z","duration":90,
                 "timezone":"Asia/Colombo","type":9,"settings":{"approval_type":0,"registration_type":1},
                 "occurrences":[{"occurrence_id":"1794480600000","start_time":"2026-11-12T11:00:00Z","duration":90,"status":"available"},
                                {"occurrence_id":"1794567000000","start_time":"2026-11-13T11:00:00Z","duration":90,"status":"available"}]}"""));
        ZoomApiClient.Webinar webinar = client.webinar("88284191115");
        assertEquals("Physics", webinar.topic());
        assertEquals(9, webinar.type());
        assertEquals(0, webinar.approvalType());
        assertEquals(1, webinar.registrationType());
        assertEquals(2, webinar.occurrences().size());
        assertEquals(Instant.parse("2026-11-13T11:00:00Z"), webinar.occurrences().get(1).startTime());
    }

    @Test
    void participantsAndRegistrantsArePagedWithNextPageTokens() {
        routes.put("GET /v2/past_webinars/838/participants?page_size=300&next_page_token=p2", exchange -> Reply.ok("""
                {"participants":[{"id":"u3","name":"C","user_email":"c@example.invalid","join_time":"2026-11-13T05:40:00Z",
                 "leave_time":"2026-11-13T06:00:00Z","duration":1200}],"next_page_token":""}"""));
        routes.put("GET /v2/past_webinars/838/participants?page_size=300", exchange -> Reply.ok("""
                {"participants":[{"id":"u1","user_id":"16778240","name":"A","user_email":"a@example.invalid",
                 "join_time":"2026-11-13T05:30:00Z","leave_time":"2026-11-13T05:35:00Z","duration":300,"registrant_id":"r1",
                 "status":"in_meeting"},
                 {"id":"u1","name":"A","user_email":"a@example.invalid","join_time":"2026-11-13T05:36:00Z",
                 "leave_time":"2026-11-13T06:00:00Z","duration":1440}],"next_page_token":"p2"}"""));
        List<ZoomApiClient.Participant> participants = client.pastParticipants("838");
        assertEquals(3, participants.size(), "one entry per join session");
        assertEquals("r1", participants.get(0).registrantId());
        assertEquals(1440, participants.get(1).durationSeconds());
        assertFalse(participants.get(0).toString().contains("a@example.invalid"), "personal data is not printed");

        routes.put("GET /v2/webinars/838/registrants?status=approved&occurrence_id=1794567000000&page_size=300", exchange -> Reply.ok("""
                {"total_records":1,"registrants":[{"id":"r1","email":"a@example.invalid","first_name":"A","last_name":"B",
                 "status":"approved","create_time":"2026-11-12T10:00:00Z"}]}"""));
        List<ZoomApiClient.Registrant> registrants = client.registrants("838", "1794567000000", "approved");
        assertEquals(1, registrants.size());
        assertEquals("a@example.invalid", registrants.get(0).email());

        routes.put("GET /v2/webinars/838/registrants?status=approved&page_size=1", exchange -> Reply.ok("{\"total_records\":42,\"registrants\":[]}"));
        assertEquals(42, client.registrantCount("838", "approved"));
    }

    @Test
    void uuidsThatStartWithASlashOrContainTwoAreEncodedTwice() {
        assertEquals("83834428148", ZoomApiClient.pathId("83834428148"));
        assertEquals("abc%3D%3D", ZoomApiClient.pathId("abc=="));
        assertEquals("%252Fabc%253D%253D", ZoomApiClient.pathId("/abc=="));
        assertEquals("ab%252F%252Fcd%253D%253D", ZoomApiClient.pathId("ab//cd=="));
        routes.put("GET /v2/past_webinars/%252Fabc%253D%253D/participants", exchange -> Reply.ok("{\"participants\":[]}"));
        assertEquals(List.of(), client.pastParticipants("/abc=="));
        assertThrows(IllegalArgumentException.class, () -> ZoomApiClient.pathId(" "));
    }

    @Test
    void errorsCarryZoomsCodeAndRetryAfterButNeverTheCredentials() {
        routes.put("GET /v2/past_webinars/9/instances", exchange ->
                new Reply(429, "{\"code\":429,\"message\":\"You have reached the maximum per-second rate limit.\"}", Map.of("Retry-After", "7")));
        ZoomApiException limited = assertThrows(ZoomApiException.class, () -> client.pastInstances("9"));
        assertTrue(limited.rateLimited());
        assertEquals(Duration.ofSeconds(7), limited.retryAfter());
        assertEquals("Zoom API list past webinar instances failed with HTTP 429 code 429: You have reached the maximum per-second rate limit.",
                limited.getMessage());

        ZoomApiException missing = assertThrows(ZoomApiException.class, () -> client.webinar("404404404"));
        assertTrue(missing.notFound());
        assertNull(missing.retryAfter());

        ZoomApiClient wrong = new ZoomApiClient(new ZoomApiProperties(ACCOUNT, CLIENT, "other-fixture-value",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token"),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2"), Duration.ofSeconds(5)), HttpClient.newHttpClient(), clock);
        ZoomApiException rejected = assertThrows(ZoomApiException.class, () -> wrong.webinar("1"));
        assertEquals(401, rejected.status());
        assertFalse(rejected.getMessage().contains("other-fixture-value"));
    }

    @Test
    void credentialsMustBeCompleteAndAreNeverPrinted() {
        assertThrows(IllegalArgumentException.class, () -> new ZoomApiProperties(ACCOUNT, CLIENT, null,
                URI.create("https://zoom.us/oauth/token"), URI.create("https://api.zoom.us/v2"), Duration.ofSeconds(10)));
        ZoomApiProperties off = new ZoomApiProperties(null, null, null, URI.create("https://zoom.us/oauth/token"),
                URI.create("https://api.zoom.us/v2"), Duration.ofSeconds(10));
        assertFalse(off.enabled());
        assertThrows(IllegalStateException.class, () -> new ZoomApiClient(off));
        ZoomApiProperties on = new ZoomApiProperties(ACCOUNT, CLIENT, CLIENT_VALUE, URI.create("https://zoom.us/oauth/token"),
                URI.create("https://api.zoom.us/v2"), Duration.ofSeconds(10));
        assertFalse(on.toString().contains(CLIENT_VALUE) || on.toString().contains(CLIENT) || on.toString().contains(ACCOUNT));
    }

    private String check(String webinarId, List<String> allowList, int[] exitCode) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        var provider = new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("zoom", client))
                .getBeanProvider(ZoomApiClient.class);
        var webhook = new com.physicscube.luma.zoom.ZoomWebhookProperties(null, null, Duration.ofMinutes(5), 65536, allowList);
        new ZoomApiCheckCommand(provider, webhook, webinarId, new java.io.PrintStream(bytes, true, StandardCharsets.UTF_8),
                code -> exitCode[0] = code).run(null);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theOperatorCheckReadsOnlyAllowListedWebinarsAndPrintsNoIdentities() {
        int[] code = {-1};
        String refused = check("88284191115", List.of("83834428148"), code);
        assertEquals(2, code[0]);
        assertTrue(refused.startsWith("Zoom API check refused: webinar 88284191115 is not in"), refused);
        assertTrue(requests.isEmpty(), "nothing is sent to Zoom for a webinar outside the allow-list");

        routes.put("GET /v2/webinars/83834428148?show_previous_occurrences=true", exchange -> Reply.ok(
                "{\"id\":83834428148,\"topic\":\"LUMA test\",\"type\":5,\"start_time\":\"2026-11-13T05:30:00Z\","
                        + "\"duration\":60,\"timezone\":\"Asia/Colombo\",\"settings\":{\"approval_type\":0}}"));
        routes.put("GET /v2/webinars/83834428148/registrants?status=approved", exchange -> Reply.ok("{\"total_records\":3}"));
        routes.put("GET /v2/webinars/83834428148/registrants?status=pending", exchange -> Reply.ok("{\"total_records\":0}"));
        routes.put("GET /v2/past_webinars/83834428148/instances", exchange -> Reply.ok(
                "{\"webinars\":[{\"uuid\":\"/inst1==\",\"start_time\":\"2026-11-13T05:30:00Z\"}]}"));
        routes.put("GET /v2/past_webinars/%252Finst1%253D%253D/participants", exchange -> Reply.ok(
                "{\"participants\":[{\"id\":\"a\",\"name\":\"Tester One\",\"user_email\":\"one@example.invalid\"},"
                        + "{\"id\":\"a\",\"name\":\"Tester One\",\"user_email\":\"one@example.invalid\"},"
                        + "{\"id\":\"b\",\"name\":\"Tester Two\",\"user_email\":\"two@example.invalid\"}]}"));
        String output = check("838 3442 8148", List.of("83834428148"), code);
        assertEquals(0, code[0], output);
        assertTrue(output.contains("Registrants: 3 approved, 0 pending"), output);
        assertTrue(output.contains("3 join sessions by 2 people (identities not shown)"), output);
        assertFalse(output.contains("example.invalid") || output.contains("Tester"), "no names or emails are printed");
    }
}
