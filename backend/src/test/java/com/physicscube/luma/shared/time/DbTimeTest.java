package com.physicscube.luma.shared.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DbTimeTest {
    @Test
    void dropsDigitsPostgresCannotStore() {
        assertEquals(Instant.parse("2026-11-11T07:52:53.711622Z"),
                DbTime.truncate(Instant.parse("2026-11-11T07:52:53.711622818Z")));
        assertEquals(Instant.parse("2026-11-11T07:52:53.711622Z"),
                DbTime.truncate(Instant.parse("2026-11-11T07:52:53.711622Z")));
    }

    @Test
    void nowIsAlreadyAtDatabasePrecision() {
        assertEquals(0, DbTime.now().getNano() % 1_000);
    }
}
