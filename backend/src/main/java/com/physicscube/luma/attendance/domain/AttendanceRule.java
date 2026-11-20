package com.physicscube.luma.attendance.domain;

import java.time.Duration;

/**
 * The academy's attendance rule (it is recorded by staff, never chosen by LUMA), applied to
 * the facts from Zoom's participant report:
 * <ul>
 *   <li>PRESENT when connected for at least {@code minConnectedPercent} of the class time;</li>
 *   <li>LATE when present but the first join was more than {@code lateAfterMinutes} after the start;</li>
 *   <li>ABSENT otherwise, including a student the report never lists.</li>
 * </ul>
 * Whole seconds; the share is compared without rounding ({@code connected × 100 ≥ percent × class}).
 */
public record AttendanceRule(int minConnectedPercent, int lateAfterMinutes) {
    public enum Outcome { PRESENT, LATE, ABSENT }

    public AttendanceRule {
        if (minConnectedPercent < 1 || minConnectedPercent > 100) {
            throw new IllegalArgumentException("minConnectedPercent must be 1 to 100");
        }
        if (lateAfterMinutes < 0 || lateAfterMinutes > 240) {
            throw new IllegalArgumentException("lateAfterMinutes must be 0 to 240");
        }
    }

    /**
     * @param secondsAfterStart {@code null} when never seen; 0 when on time or early
     */
    public Outcome decide(Duration classLength, long connectedSeconds, Long secondsAfterStart) {
        if (classLength.isNegative() || classLength.isZero()) {
            throw new IllegalArgumentException("classLength must be positive");
        }
        if (connectedSeconds < 0) {
            throw new IllegalArgumentException("connectedSeconds must not be negative");
        }
        boolean enough = connectedSeconds * 100 >= (long) minConnectedPercent * classLength.toSeconds();
        if (!enough || secondsAfterStart == null) {
            return Outcome.ABSENT;
        }
        return secondsAfterStart > lateAfterMinutes * 60L ? Outcome.LATE : Outcome.PRESENT;
    }
}
