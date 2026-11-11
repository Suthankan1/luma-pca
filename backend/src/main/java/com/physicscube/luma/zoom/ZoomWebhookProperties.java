package com.physicscube.luma.zoom;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Webhook ingress is off unless a secret token is configured. One academy per
 * deployment for now; per-academy Zoom credentials arrive with 003A.
 *
 * @param maxClockSkew how far x-zm-request-timestamp may be from our clock. Zoom
 *     documents no window; five minutes is our choice and must be checked against
 *     how Zoom timestamps retried deliveries.
 */
@ConfigurationProperties("luma.zoom.webhook")
public record ZoomWebhookProperties(
        String secretToken,
        UUID academyId,
        @DefaultValue("PT5M") Duration maxClockSkew,
        @DefaultValue("65536") int maxBodyBytes) {

    public ZoomWebhookProperties {
        if (enabled(secretToken)) {
            if (secretToken.strip().length() < 16) {
                throw new IllegalArgumentException("luma.zoom.webhook.secret-token is too short");
            }
            if (academyId == null) {
                throw new IllegalArgumentException("luma.zoom.webhook.academy-id is required when webhooks are enabled");
            }
        }
        if (maxClockSkew.isNegative() || maxClockSkew.isZero() || maxClockSkew.compareTo(Duration.ofMinutes(30)) > 0
                || maxBodyBytes < 1024 || maxBodyBytes > 1_048_576) {
            throw new IllegalArgumentException("luma.zoom.webhook limits are out of range");
        }
    }

    public boolean enabled() {
        return enabled(secretToken);
    }

    private static boolean enabled(String token) {
        return token != null && !token.isBlank();
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "ZoomWebhookProperties[enabled=" + enabled() + ", academyId=" + academyId + ", maxClockSkew=" + maxClockSkew
                + ", maxBodyBytes=" + maxBodyBytes + "]";
    }
}
