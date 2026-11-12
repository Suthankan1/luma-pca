package com.physicscube.luma.identity;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Email to account holders (invitations, password resets); off unless an SMTP server,
 * a sender and the web origin are configured.
 */
@ConfigurationProperties("luma.mail")
public record MailProperties(
        @DefaultValue("false") boolean enabled,
        String from,
        /** The Next.js origin the emailed links open, such as https://luma.academy.example. */
        String webOrigin) {

    public MailProperties {
        if (enabled) {
            if (from == null || !from.matches("^[^\\s@<>]+@[^\\s@<>]+$")) {
                throw new IllegalArgumentException("luma.mail.from must be a plain email address when enabled");
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
            throw new IllegalArgumentException("luma.mail.web-origin is not a valid URL");
        }
        String host = uri.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
        boolean schemeOk = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local);
        boolean bare = (uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
        if (host == null || !schemeOk || !bare) {
            throw new IllegalArgumentException("luma.mail.web-origin must be an https origin (http only for localhost)");
        }
        return uri.getScheme() + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }
}
