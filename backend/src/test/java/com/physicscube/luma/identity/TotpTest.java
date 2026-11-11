package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class TotpTest {
    /** The SHA-1 seed from RFC 6238 Appendix B. */
    private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Instant NOW = Instant.ofEpochSecond(1_111_111_109L);

    @Test
    void matchesRfc6238TestVectorsTruncatedToSixDigits() {
        assertEquals("287082", Totp.code(RFC_SEED, Totp.step(Instant.ofEpochSecond(59))));
        assertEquals("081804", Totp.code(RFC_SEED, Totp.step(Instant.ofEpochSecond(1_111_111_109L))));
        assertEquals("050471", Totp.code(RFC_SEED, Totp.step(Instant.ofEpochSecond(1_111_111_111L))));
        assertEquals("005924", Totp.code(RFC_SEED, Totp.step(Instant.ofEpochSecond(1_234_567_890L))));
        assertEquals("279037", Totp.code(RFC_SEED, Totp.step(Instant.ofEpochSecond(2_000_000_000L))));
        assertEquals("353130", Totp.code(RFC_SEED, Totp.step(Instant.ofEpochSecond(20_000_000_000L))));
    }

    @Test
    void codesUseAsciiDigitsOnHostsWithLocalizedNumberFormats() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
            long step = Totp.step(NOW);
            assertEquals("081804", Totp.code(RFC_SEED, step));
            assertEquals(step, Totp.match(RFC_SEED, "081804", NOW, null));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    void acceptsTheCurrentStepAndOneEitherSide() {
        long current = Totp.step(NOW);
        for (long step = current - 1; step <= current + 1; step++) {
            assertEquals(step, Totp.match(RFC_SEED, Totp.code(RFC_SEED, step), NOW, null));
        }
        assertEquals(-1, Totp.match(RFC_SEED, Totp.code(RFC_SEED, current - 2), NOW, null));
        assertEquals(-1, Totp.match(RFC_SEED, Totp.code(RFC_SEED, current + 2), NOW, null));
    }

    @Test
    void aCodeCannotBeReplayedOrFollowedByAnOlderOne() {
        long current = Totp.step(NOW);
        String code = Totp.code(RFC_SEED, current);

        assertEquals(-1, Totp.match(RFC_SEED, code, NOW, current));
        assertEquals(-1, Totp.match(RFC_SEED, Totp.code(RFC_SEED, current - 1), NOW, current));
        assertEquals(current + 1, Totp.match(RFC_SEED, Totp.code(RFC_SEED, current + 1), NOW, current));
        assertEquals(current, Totp.match(RFC_SEED, code, NOW, current - 1));
    }

    @Test
    void rejectsWrongAndMalformedCodes() {
        byte[] otherSecret = "09876543210987654321".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1, Totp.match(otherSecret, Totp.code(RFC_SEED, Totp.step(NOW)), NOW, null));
        for (String malformed : new String[] {null, "", "12345", "1234567", "12345a", " 81804", "081 804", "०८१८०४"}) {
            assertEquals(-1, Totp.match(RFC_SEED, malformed, NOW, null), String.valueOf(malformed));
        }
    }

    @Test
    void base32RoundTripsAndMatchesTheKnownEncoding() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(RFC_SEED));
        assertArrayEquals(RFC_SEED, Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        assertArrayEquals(RFC_SEED, Base32.decode("gezdgnbvgy3tqojqgezdgnbvgy3tqojq"));
        for (int length = 0; length < 12; length++) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i * 37 + 201);
            }
            assertArrayEquals(data, Base32.decode(Base32.encode(data)));
        }
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("ABC1"));
    }
}
