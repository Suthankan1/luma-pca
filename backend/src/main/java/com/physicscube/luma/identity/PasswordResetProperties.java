package com.physicscube.luma.identity;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Self-service password reset by email; off unless an SMTP server and sender are configured. */
@ConfigurationProperties("luma.password-reset")
public record PasswordResetProperties(
        @DefaultValue("false") boolean enabled,
        String from,
        /** The Next.js origin the emailed link opens, such as https://luma.academy.example. */
        String webOrigin,
        @DefaultValue("PT30M") Duration ttl,
        @DefaultValue("3") int maxRequestsPerHour) {

    public PasswordResetProperties {
        if (ttl.compareTo(Duration.ofMinutes(5)) < 0 || ttl.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("luma.password-reset.ttl must be between 5 minutes and 2 hours");
        }
        if (maxRequestsPerHour < 1 || maxRequestsPerHour > 10) {
            throw new IllegalArgumentException("luma.password-reset.max-requests-per-hour must be between 1 and 10");
        }
        if (enabled) {
            if (from == null || !from.matches("^[^\\s@<>]+@[^\\s@<>]+$")) {
                throw new IllegalArgumentException("luma.password-reset.from must be a plain email address when enabled");
            }
            webOrigin = origin(webOrigin);
        }
    }

    /** https://host[:port], or http only for localhost and 127.0.0.1; no path, query or fragment. */
    static String origin(String raw) {
        URI uri;
        try {
            uri = URI.create(raw == null ? "" : raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("luma.password-reset.web-origin is not a valid URL");
        }
        String host = uri.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
        boolean schemeOk = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local);
        boolean bare = (uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
        if (host == null || !schemeOk || !bare) {
            throw new IllegalArgumentException("luma.password-reset.web-origin must be an https origin (http only for localhost)");
        }
        return uri.getScheme() + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }
}
