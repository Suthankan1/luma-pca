package com.physicscube.luma.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceDurationCalculatorTest {
    private static final Instant START = Instant.parse("2026-11-11T08:00:00Z");
    private static final Instant END = Instant.parse("2026-11-11T10:00:00Z");

    @Test
    void mergesOverlappingAndDuplicatedIntervalsInsteadOfDoubleCounting() {
        var a = new ConnectionInterval(START.plusSeconds(60), START.plusSeconds(1800));
        var b = new ConnectionInterval(START.plusSeconds(1200), START.plusSeconds(2400));
        assertThat(AttendanceDurationCalculator.connectedDuration(START, END, List.of(a, b, a)))
                .isEqualTo(Duration.ofMinutes(39));
    }

    @Test
    void excludesOfflineGapsAndClipsOutsideScheduledClass() {
        var a = new ConnectionInterval(START.minusSeconds(600), START.plusSeconds(1800));
        var b = new ConnectionInterval(START.plusSeconds(2400), END.plusSeconds(3600));
        assertThat(AttendanceDurationCalculator.connectedDuration(START, END, List.of(a, b)))
                .isEqualTo(Duration.ofMinutes(110));
    }

    @Test
    void emptyObservationsCountAsZero() {
        assertThat(AttendanceDurationCalculator.connectedDuration(START, END, List.of())).isEqualTo(Duration.ZERO);
    }

    @Test
    void rejectsMalformedWindow() {
        assertThatThrownBy(() -> AttendanceDurationCalculator.connectedDuration(END, START, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
