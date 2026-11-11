package com.physicscube.luma.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {
    @Test
    void trimsAndLowercases() {
        assertEquals("student.one@example.invalid", EmailAddress.normalize("  Student.One@Example.INVALID \n"));
    }

    @Test
    void lowercasingDoesNotDependOnTheDefaultLocale() {
        // Under a Turkish locale "I" would otherwise become a dotless "ı".
        assertEquals("iris@example.invalid", EmailAddress.normalize("IRIS@EXAMPLE.INVALID"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "no-at-sign", "@example.invalid", "a@b", "a b@example.invalid",
        "a@@example.invalid", "a@example..invalid", "a@.example.invalid"})
    void rejectsMalformedAddresses(String raw) {
        assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize(raw));
    }

    @Test
    void rejectsOverlongAddresses() {
        String email = "a@" + "b".repeat(EmailAddress.MAX_LENGTH) + ".invalid";
        assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize(email));
    }
}
