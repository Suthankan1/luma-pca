package com.physicscube.luma.zoom;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Receipt processing. Retry delay doubles from {@code initialBackoff} up to
 * {@code maxBackoff}; after {@code maxAttempts} failures a receipt is dead-lettered.
 * With the defaults the waits are 30 s, 1, 2, 4, 8, 16 and 32 min, then 1 h twice:
 * ten attempts spread over about 3 hours.
 */
@ConfigurationProperties("luma.zoom.processing")
public record ZoomProcessingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT5S") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("PT30S") Duration initialBackoff,
        @DefaultValue("PT1H") Duration maxBackoff) {

    public ZoomProcessingProperties {
        if (batchSize < 1 || batchSize > 500 || maxAttempts < 1 || maxAttempts > 50
                || pollInterval.compareTo(Duration.ofMillis(100)) < 0
                || initialBackoff.isNegative() || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("luma.zoom.processing settings are out of range");
        }
    }

    /** Delay before the next try after {@code failedAttempts} failures (1 = first failure). */
    public Duration backoffAfter(int failedAttempts) {
        long factor = 1L << Math.min(Math.max(failedAttempts - 1, 0), 20);
        Duration delay = initialBackoff.multipliedBy(factor);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
