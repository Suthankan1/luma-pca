package com.physicscube.luma.zoom.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;

/**
 * Read-only calls to the Zoom REST API with a Server-to-Server OAuth app (slice 003A). Only
 * the four endpoints LUMA's app has scopes for. Tokens last an hour and are reused until a
 * minute before they expire; a 401 fetches a new one once. Responses about people (names,
 * emails) are returned to the caller and never logged.
 */
public class ZoomApiClient {
    private static final Logger log = LoggerFactory.getLogger(ZoomApiClient.class);
    private static final int PAGE_SIZE = 300;
    private static final int MAX_PAGES = 100;

    public record Occurrence(String occurrenceId, Instant startTime, int duration, String status) {
    }

    public record Webinar(long id, String uuid, String topic, Instant startTime, int duration, String timezone, int type,
                          Integer approvalType, Integer registrationType, List<Occurrence> occurrences) {
    }

    public record PastInstance(String uuid, Instant startTime) {
    }

    /** One join session; Zoom lists a person once per join. Personal data: do not log. */
    public record Participant(String id, String userId, String name, String userEmail, Instant joinTime, Instant leaveTime,
                              int durationSeconds, String registrantId, String status) {
        @Override
        public String toString() {
            return "Participant[<personal data>]";
        }
    }

    /** Personal data: do not log. */
    public record Registrant(String id, String email, String firstName, String lastName, String status, Instant createTime) {
        @Override
        public String toString() {
            return "Registrant[<personal data>]";
        }
    }

    private record Token(String value, Instant refreshAfter, String scope) {
    }

    private final ZoomApiProperties properties;
    private final HttpClient http;
    private final Clock clock;
    private Token token;

    public ZoomApiClient(ZoomApiProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.timeout()).followRedirects(HttpClient.Redirect.NEVER).build(),
                Clock.systemUTC());
    }

    ZoomApiClient(ZoomApiProperties properties, HttpClient http, Clock clock) {
        if (!properties.enabled()) {
            throw new IllegalStateException("Zoom API credentials are not configured");
        }
        this.properties = properties;
        this.http = http;
        this.clock = clock;
    }

    /** The scopes Zoom granted with the current token (space-separated), for operator checks. */
    public synchronized String grantedScopes() {
        return accessToken(false).scope();
    }

    public Webinar webinar(String webinarId) {
        Map<String, Object> json = get("get webinar", "/webinars/" + pathId(webinarId), Map.of("show_previous_occurrences", "true"));
        List<Occurrence> occurrences = new ArrayList<>();
        if (json.get("occurrences") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> o) {
                    occurrences.add(new Occurrence(text(o.get("occurrence_id")), instant(o.get("start_time")),
                            number(o.get("duration")), text(o.get("status"))));
                }
            }
        }
        Map<?, ?> settings = json.get("settings") instanceof Map<?, ?> s ? s : Map.of();
        return new Webinar(((Number) json.get("id")).longValue(), text(json.get("uuid")), text(json.get("topic")),
                instant(json.get("start_time")), number(json.get("duration")), text(json.get("timezone")), number(json.get("type")),
                settings.get("approval_type") instanceof Number n ? n.intValue() : null,
                settings.get("registration_type") instanceof Number n ? n.intValue() : null, occurrences);
    }

    public List<PastInstance> pastInstances(String webinarId) {
        Map<String, Object> json = get("list past webinar instances", "/past_webinars/" + pathId(webinarId) + "/instances", Map.of());
        List<PastInstance> result = new ArrayList<>();
        if (json.get("webinars") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> w) {
                    result.add(new PastInstance(text(w.get("uuid")), instant(w.get("start_time"))));
                }
            }
        }
        return result;
    }

    /** @param webinarIdOrUuid a webinar ID (latest instance) or one instance's UUID */
    public List<Participant> pastParticipants(String webinarIdOrUuid) {
        return pages("list past webinar participants", "/past_webinars/" + pathId(webinarIdOrUuid) + "/participants", Map.of(), "participants",
                p -> new Participant(text(p.get("id")), text(p.get("user_id")), text(p.get("name")), text(p.get("user_email")),
                        instant(p.get("join_time")), instant(p.get("leave_time")), number(p.get("duration")),
                        text(p.get("registrant_id")), text(p.get("status"))));
    }

    /** @param occurrenceId null for all occurrences; status approved, pending or denied */
    public List<Registrant> registrants(String webinarId, String occurrenceId, String status) {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        query.put("status", status);
        if (occurrenceId != null) {
            query.put("occurrence_id", occurrenceId);
        }
        return pages("list webinar registrants", "/webinars/" + pathId(webinarId) + "/registrants", query, "registrants",
                r -> new Registrant(text(r.get("id")), text(r.get("email")), text(r.get("first_name")), text(r.get("last_name")),
                        text(r.get("status")), instant(r.get("create_time"))));
    }

    /** Only the total, for checks that must not read personal data. */
    public int registrantCount(String webinarId, String status) {
        Map<String, String> query = new java.util.LinkedHashMap<>();
        query.put("status", status);
        query.put("page_size", "1");
        Map<String, Object> json = get("list webinar registrants", "/webinars/" + pathId(webinarId) + "/registrants", query);
        return number(json.get("total_records"));
    }

    /**
     * Zoom requires a UUID that starts with "/" or contains "//" to be encoded twice; anything
     * else (including plain webinar IDs) once.
     */
    static String pathId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A webinar ID or UUID is required");
        }
        String once = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        return id.startsWith("/") || id.contains("//") ? URLEncoder.encode(once, StandardCharsets.UTF_8) : once;
    }

    private <T> List<T> pages(String label, String path, Map<String, String> query, String field, Function<Map<?, ?>, T> map) {
        List<T> result = new ArrayList<>();
        String next = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> q = new java.util.LinkedHashMap<>(query);
            q.put("page_size", Integer.toString(PAGE_SIZE));
            if (next != null) {
                q.put("next_page_token", next);
            }
            Map<String, Object> json = get(label, path, q);
            if (json.get(field) instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        result.add(map.apply(m));
                    }
                }
            }
            next = json.get("next_page_token") instanceof String s && !s.isBlank() ? s : null;
            if (next == null) {
                return result;
            }
        }
        throw new ZoomApiException("Zoom returned more than " + MAX_PAGES + " pages", 0, null);
    }

    private Map<String, Object> get(String label, String path, Map<String, String> query) {
        StringBuilder uri = new StringBuilder(properties.apiBaseUrl().toString()).append(path);
        String separator = "?";
        for (Map.Entry<String, String> e : query.entrySet()) {
            uri.append(separator).append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            separator = "&";
        }
        HttpResponse<String> response = send(URI.create(uri.toString()), false);
        if (response.statusCode() == 401) {
            // The token may have been revoked or expired early: fetch a new one once.
            response = send(URI.create(uri.toString()), true);
        }
        if (response.statusCode() / 100 != 2) {
            throw failure(response, "Zoom API " + label);
        }
        return parse(response.body());
    }

    private HttpResponse<String> send(URI uri, boolean freshToken) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.timeout())
                .header("Authorization", "Bearer " + accessToken(freshToken).value()).header("Accept", "application/json").GET().build();
        return exchange(request);
    }

    private synchronized Token accessToken(boolean forceNew) {
        if (!forceNew && token != null && clock.instant().isBefore(token.refreshAfter())) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        URI uri = URI.create(properties.tokenUrl() + "?grant_type=account_credentials&account_id="
                + URLEncoder.encode(properties.accountId(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(properties.timeout()).header("Authorization", "Basic " + basic)
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = exchange(request);
        if (response.statusCode() != 200) {
            throw failure(response, "Zoom token request");
        }
        Map<String, Object> json = parse(response.body());
        if (!(json.get("access_token") instanceof String value) || value.isBlank()) {
            throw new ZoomApiException("Zoom token response has no access_token", response.statusCode(), null);
        }
        long seconds = json.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        token = new Token(value, clock.instant().plusSeconds(Math.max(0, seconds - 60)), text(json.get("scope")));
        log.info("Obtained a Zoom access token valid for {} s", seconds);
        return token;
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ZoomApiException("Zoom could not be reached: " + e.getClass().getSimpleName(), 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZoomApiException("Interrupted while calling Zoom", 0, null);
        }
    }

    /** Zoom's own error code and message (they describe the request, not people), plus Retry-After for 429. */
    private static ZoomApiException failure(HttpResponse<String> response, String what) {
        String code = "";
        try {
            Map<String, Object> json = JsonParserFactory.getJsonParser().parseMap(response.body());
            if (json.get("code") != null) {
                code = " code " + json.get("code");
            }
            if (json.get("message") instanceof String message && message.length() <= 200) {
                code += ": " + message;
            }
        } catch (IllegalArgumentException e) {
            // not JSON; status alone
        }
        Duration retryAfter = response.headers().firstValue("Retry-After").flatMap(ZoomApiClient::seconds).orElse(null);
        return new ZoomApiException(what + " failed with HTTP " + response.statusCode() + code, response.statusCode(), retryAfter);
    }

    private static java.util.Optional<Duration> seconds(String header) {
        try {
            return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static Map<String, Object> parse(String body) {
        try {
            return JsonParserFactory.getJsonParser().parseMap(body);
        } catch (IllegalArgumentException e) {
            throw new ZoomApiException("Zoom returned a response that is not a JSON object", 0, null);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Instant instant(Object value) {
        try {
            return value instanceof String s && !s.isBlank() ? Instant.parse(s) : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
