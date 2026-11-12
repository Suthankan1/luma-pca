package com.physicscube.luma.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limits for self-service reset; whether it is available follows {@link MailProperties#enabled()}. */
@ConfigurationProperties("luma.password-reset")
public record PasswordResetProperties(
        @DefaultValue("PT30M") Duration ttl,
        @DefaultValue("3") int maxRequestsPerHour) {

    public PasswordResetProperties {
        if (ttl.compareTo(Duration.ofMinutes(5)) < 0 || ttl.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("luma.password-reset.ttl must be between 5 minutes and 2 hours");
        }
        if (maxRequestsPerHour < 1 || maxRequestsPerHour > 10) {
            throw new IllegalArgumentException("luma.password-reset.max-requests-per-hour must be between 1 and 10");
        }
    }
}
