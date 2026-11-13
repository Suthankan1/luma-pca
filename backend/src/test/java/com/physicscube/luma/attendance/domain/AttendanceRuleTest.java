package com.physicscube.luma.attendance.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AttendanceRuleTest {
    static Stream<org.junit.jupiter.params.provider.Arguments> golden() throws IOException {
        try (InputStream in = AttendanceRuleTest.class.getResourceAsStream("/attendance/golden-outcomes.csv")) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
            return lines.stream().map(l -> org.junit.jupiter.params.provider.Arguments.of((Object) l.split(",", -1)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("golden")
    void matchesTheGoldenDataset(String[] row) {
        AttendanceRule rule = new AttendanceRule(Integer.parseInt(row[2]), Integer.parseInt(row[3]));
        Long after = row[5].isEmpty() ? null : Long.parseLong(row[5]);
        assertEquals(AttendanceRule.Outcome.valueOf(row[6]),
                rule.decide(Duration.ofMinutes(Integer.parseInt(row[1])), Long.parseLong(row[4]), after), row[0]);
    }

    @Test
    void goldenDatasetIsNotEmpty() throws IOException {
        assertEquals(15, golden().count());
    }

    @Test
    void refusesImpossibleRulesAndInputs() {
        assertThrows(IllegalArgumentException.class, () -> new AttendanceRule(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new AttendanceRule(101, 10));
        assertThrows(IllegalArgumentException.class, () -> new AttendanceRule(75, -1));
        AttendanceRule rule = new AttendanceRule(75, 10);
        assertThrows(IllegalArgumentException.class, () -> rule.decide(Duration.ZERO, 0, 0L));
        assertThrows(IllegalArgumentException.class, () -> rule.decide(Duration.ofMinutes(60), -1, 0L));
    }
}
