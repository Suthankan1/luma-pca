package com.physicscube.luma.zoom;

import java.time.Duration;
import java.util.List;
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
 * @param webinarAllowList when not empty, only events for these webinar IDs are stored;
 *     events for any other webinar are acknowledged and dropped unread. A Zoom event
 *     subscription covers the whole account, so this keeps a test deployment from
 *     collecting attendees of real classes.
 */
@ConfigurationProperties("luma.zoom.webhook")
public record ZoomWebhookProperties(
        String secretToken,
        UUID academyId,
        @DefaultValue("PT5M") Duration maxClockSkew,
        @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue List<String> webinarAllowList) {

    public ZoomWebhookProperties {
        if (enabled(secretToken)) {
            if (secretToken.strip().length() < 16) {
                throw new IllegalArgumentException("luma.zoom.webhook.secret-token is too short");
            }
            if (academyId == null) {
                throw new IllegalArgumentException("luma.zoom.webhook.academy-id is required when webhooks are enabled");
            }
        }
        webinarAllowList = webinarAllowList.stream().map(id -> id.replaceAll("[\\s-]", "")).filter(id -> !id.isEmpty()).toList();
        if (webinarAllowList.stream().anyMatch(id -> !id.matches("[0-9]{9,20}"))) {
            throw new IllegalArgumentException("luma.zoom.webhook.webinar-allow-list must list Zoom webinar IDs (digits)");
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
                + ", maxBodyBytes=" + maxBodyBytes + ", webinarAllowList=" + webinarAllowList + "]";
    }
}
