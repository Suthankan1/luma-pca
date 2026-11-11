package com.physicscube.luma.zoom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.physicscube.luma.zoom.ZoomWebhookSignature.Result;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reference values were computed independently with Python's hmac module. */
class ZoomWebhookSignatureTest {
    static final String FIXTURE_KEY = "fixture-key-for-zoom-signature-tests";
    static final byte[] BODY = ("{\"event\":\"webinar.participant_joined\",\"event_ts\":1795000000000,\"payload\":{\"object\":"
            + "{\"id\":\"81234567890\",\"uuid\":\"Abc123==\"}}}").getBytes(StandardCharsets.UTF_8);
    static final String TIMESTAMP = "1795000000";
    static final String REFERENCE = "v0=02b77f9c6c0b339648f8247fe74f6324d45876f450765b89250b1c9f5f4c54e2";
    private static final Instant AT = Instant.ofEpochSecond(1_795_000_000L);
    private static final Duration SKEW = Duration.ofMinutes(5);

    private final ZoomWebhookSignature signature = new ZoomWebhookSignature(FIXTURE_KEY);

    @Test
    void acceptsZoomsDocumentedSignature() {
        assertEquals(Result.VALID, signature.verify(TIMESTAMP, REFERENCE, BODY, AT, SKEW));
        assertEquals(Result.VALID, new ZoomWebhookSignature("  " + FIXTURE_KEY + "\n").verify(TIMESTAMP, REFERENCE, BODY, AT, SKEW),
                "surrounding whitespace in configuration is ignored");
    }

    @Test
    void answersUrlValidationWithTheHmacOfThePlainToken() {
        assertEquals("7d5bee6a2927ccb4ee0caaa11b22a5a99a90dd28ad4422d9a347372f18454802",
                signature.validationToken("qgg8vlvZRS6UYooatFL8Aw"));
    }

    @Test
    void rejectsAnyChangeToBodyTimestampOrKey() {
        byte[] tampered = BODY.clone();
        tampered[tampered.length - 3] = '9';
        assertEquals(Result.MISMATCH, signature.verify(TIMESTAMP, REFERENCE, tampered, AT, SKEW));
        assertEquals(Result.MISMATCH, signature.verify("1795000001", REFERENCE, BODY, AT, SKEW));
        assertEquals(Result.MISMATCH, new ZoomWebhookSignature(FIXTURE_KEY + "x").verify(TIMESTAMP, REFERENCE, BODY, AT, SKEW));
        byte[] padded = (new String(BODY, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        assertEquals(Result.MISMATCH, signature.verify(TIMESTAMP, REFERENCE, padded, AT, SKEW), "raw bytes, not parsed JSON");
    }

    @Test
    void enforcesTheFreshnessWindowBothWays() {
        assertEquals(Result.VALID, signature.verify(TIMESTAMP, REFERENCE, BODY, AT.plus(SKEW), SKEW));
        assertEquals(Result.VALID, signature.verify(TIMESTAMP, REFERENCE, BODY, AT.minus(SKEW), SKEW));
        assertEquals(Result.STALE, signature.verify(TIMESTAMP, REFERENCE, BODY, AT.plus(SKEW).plusSeconds(1), SKEW));
        assertEquals(Result.STALE, signature.verify(TIMESTAMP, REFERENCE, BODY, AT.minus(SKEW).minusSeconds(1), SKEW),
                "a timestamp from the future is refused too");
    }

    @Test
    void rejectsMissingOrMalformedHeadersBeforeComputing() {
        assertEquals(Result.MISSING, signature.verify(null, REFERENCE, BODY, AT, SKEW));
        assertEquals(Result.MISSING, signature.verify(TIMESTAMP, null, BODY, AT, SKEW));
        for (String bad : new String[] {"", REFERENCE.toUpperCase(), REFERENCE.replace("v0=", "v1="), REFERENCE.substring(0, 60),
            REFERENCE + "00", "v0=" + "g".repeat(64), " " + REFERENCE}) {
            assertEquals(Result.MALFORMED, signature.verify(TIMESTAMP, bad, BODY, AT, SKEW), bad);
        }
        for (String bad : new String[] {"", "-1795000000", "1795000000.5", "17950000000000", "abc"}) {
            assertEquals(Result.MALFORMED, signature.verify(bad, REFERENCE, BODY, AT, SKEW), bad);
        }
    }

    @Test
    void configurationRequiresAnAcademyAndAStrongEnoughSecretAndNeverPrintsIt() {
        ZoomWebhookProperties off = new ZoomWebhookProperties(null, null, SKEW, 65536);
        assertFalse(off.enabled());
        ZoomWebhookProperties on = new ZoomWebhookProperties(FIXTURE_KEY, UUID.randomUUID(), SKEW, 65536);
        assertTrue(on.enabled());
        assertFalse(on.toString().contains(FIXTURE_KEY));
        assertThrows(IllegalArgumentException.class, () -> new ZoomWebhookProperties(FIXTURE_KEY, null, SKEW, 65536));
        assertThrows(IllegalArgumentException.class, () -> new ZoomWebhookProperties("short", UUID.randomUUID(), SKEW, 65536));
        assertThrows(IllegalArgumentException.class, () -> new ZoomWebhookProperties(FIXTURE_KEY, UUID.randomUUID(), Duration.ZERO, 65536));
        assertThrows(IllegalArgumentException.class,
                () -> new ZoomWebhookProperties(FIXTURE_KEY, UUID.randomUUID(), Duration.ofHours(1), 65536));
        assertThrows(IllegalArgumentException.class, () -> new ZoomWebhookProperties(FIXTURE_KEY, UUID.randomUUID(), SKEW, 10));
    }
}
