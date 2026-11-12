package com.physicscube.luma.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.json.JsonParserFactory;

/** Minimal real-HTTP client for integration tests. */
public final class HttpApi {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;

    public HttpApi(int port) {
        this.base = "http://localhost:" + port;
    }

    /** Strings are quoted and must not need escaping; numbers and booleans are written as is; null is allowed. */
    public static String json(Map<String, ?> fields) {
        return fields.entrySet().stream().map(e -> '"' + e.getKey() + "\":"
                        + (e.getValue() instanceof String text ? '"' + text + '"' : String.valueOf(e.getValue())))
                .collect(Collectors.joining(",", "{", "}"));
    }

    public static Map<String, Object> parse(HttpResponse<String> response) {
        return JsonParserFactory.getJsonParser().parseMap(response.body());
    }

    public HttpResponse<String> post(String path, String body) {
        return post(path, body, null);
    }

    public HttpResponse<String> post(String path, String body, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request);
    }

    /** A POST as if relayed by a trusted proxy for the given client address. */
    public HttpResponse<String> postFrom(String clientAddress, String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .header("X-Forwarded-For", clientAddress).POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public HttpResponse<String> put(String path, String body, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request);
    }

    public HttpResponse<String> delete(String path, String bearerToken) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + bearerToken).DELETE());
    }

    public static java.util.List<Object> parseList(HttpResponse<String> response) {
        return JsonParserFactory.getJsonParser().parseList(response.body());
    }

    public HttpResponse<String> get(String path, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
