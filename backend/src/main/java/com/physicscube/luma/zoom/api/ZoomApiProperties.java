package com.physicscube.luma.zoom.api;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-to-Server OAuth credentials of the academy's Zoom app. Off unless all three are set.
 * The secret comes from the environment (a secrets manager in production) and is never logged.
 */
@ConfigurationProperties("luma.zoom.api")
public record ZoomApiProperties(
        String accountId,
        String clientId,
        String clientSecret,
        @DefaultValue("https://zoom.us/oauth/token") URI tokenUrl,
        @DefaultValue("https://api.zoom.us/v2") URI apiBaseUrl,
        @DefaultValue("PT10S") Duration timeout) {

    public ZoomApiProperties {
        boolean any = present(accountId) || present(clientId) || present(clientSecret);
        boolean all = present(accountId) && present(clientId) && present(clientSecret);
        if (any && !all) {
            throw new IllegalArgumentException("luma.zoom.api needs account-id, client-id and client-secret together");
        }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("luma.zoom.api.timeout must be between 0 and 1 minute");
        }
    }

    public boolean enabled() {
        return present(clientSecret);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** Never prints the credentials. */
    @Override
    public String toString() {
        return "ZoomApiProperties[enabled=" + enabled() + ", tokenUrl=" + tokenUrl + ", apiBaseUrl=" + apiBaseUrl + "]";
    }
}
