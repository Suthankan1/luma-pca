package com.physicscube.luma.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sending of queued (bulk) invitation emails. Each poll sends up to {@code batchSize}
 * emails, one at a time, so the defaults send at most 240 a minute; lower them for a mail
 * service with a tighter limit. A failed send is retried after {@code initialBackoff},
 * doubling up to {@code maxBackoff}, and given up after {@code maxAttempts}. A claimed
 * email is not picked up by another worker for {@code lease}, long enough to send it.
 */
@ConfigurationProperties("luma.mail.bulk")
public record BulkInvitationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT5S") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT1M") Duration initialBackoff,
        @DefaultValue("PT30M") Duration maxBackoff,
        @DefaultValue("PT10M") Duration lease) {

    public BulkInvitationProperties {
        if (batchSize < 1 || batchSize > 200 || maxAttempts < 1 || maxAttempts > 20
                || pollInterval.compareTo(Duration.ofMillis(100)) < 0 || initialBackoff.isNegative() || initialBackoff.isZero()
                || maxBackoff.compareTo(initialBackoff) < 0 || lease.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("luma.mail.bulk settings are out of range");
        }
    }

    /** Delay before the next try after {@code failedAttempts} failures (1 = first failure). */
    public Duration backoffAfter(int failedAttempts) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(Math.max(failedAttempts - 1, 0), 20));
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
