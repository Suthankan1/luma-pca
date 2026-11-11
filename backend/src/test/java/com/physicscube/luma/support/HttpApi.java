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

    /** Values must not need JSON escaping. */
    public static String json(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> '"' + e.getKey() + "\":\"" + e.getValue() + '"')
                .collect(Collectors.joining(",", "{", "}"));
    }

    public static Map<String, Object> parse(HttpResponse<String> response) {
        return JsonParserFactory.getJsonParser().parseMap(response.body());
    }

    public HttpResponse<String> post(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
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
