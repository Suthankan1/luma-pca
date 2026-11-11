package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ZoomProcessingPropertiesTest {
    private static ZoomProcessingProperties defaults() {
        return new ZoomProcessingProperties(true, Duration.ofSeconds(5), 50, 10, Duration.ofSeconds(30), Duration.ofHours(1));
    }

    @Test
    void backoffDoublesFromThirtySecondsAndIsCappedAtOneHour() {
        ZoomProcessingProperties p = defaults();
        assertEquals(Duration.ofSeconds(30), p.backoffAfter(1));
        assertEquals(Duration.ofSeconds(60), p.backoffAfter(2));
        assertEquals(Duration.ofMinutes(16), p.backoffAfter(6));
        assertEquals(Duration.ofMinutes(32), p.backoffAfter(7));
        assertEquals(Duration.ofHours(1), p.backoffAfter(8));
        assertEquals(Duration.ofHours(1), p.backoffAfter(1000), "no overflow for absurd counts");
        assertEquals(Duration.ofSeconds(30), p.backoffAfter(0));
    }

    @Test
    void defaultsRetryForAboutThreeHoursBeforeDeadLettering() {
        ZoomProcessingProperties p = defaults();
        Duration total = Duration.ZERO;
        for (int failed = 1; failed < p.maxAttempts(); failed++) {
            total = total.plus(p.backoffAfter(failed));
        }
        assertEquals(Duration.ofHours(3).plusMinutes(3).plusSeconds(30), total, "nine waits before the tenth and last attempt");
    }

    @Test
    void settingsOutOfRangeAreRejected() {
        Duration s = Duration.ofSeconds(30);
        assertThrows(IllegalArgumentException.class, () -> new ZoomProcessingProperties(true, Duration.ofSeconds(5), 0, 8, s, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new ZoomProcessingProperties(true, Duration.ofSeconds(5), 50, 0, s, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new ZoomProcessingProperties(true, Duration.ofMillis(10), 50, 8, s, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new ZoomProcessingProperties(true, Duration.ofSeconds(5), 50, 8, Duration.ZERO, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new ZoomProcessingProperties(true, Duration.ofSeconds(5), 50, 8, Duration.ofHours(2), Duration.ofHours(1)));
    }
}
