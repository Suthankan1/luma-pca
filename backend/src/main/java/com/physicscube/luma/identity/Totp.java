package com.physicscube.luma.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords with the parameters every common
 * authenticator app supports: HMAC-SHA1, 6 digits, 30-second steps.
 */
public final class Totp {
    public static final int PERIOD_SECONDS = 30;
    public static final int SECRET_BYTES = 20;
    /** Steps accepted either side of the current one, to tolerate clock drift. */
    static final int WINDOW = 1;

    private Totp() {
    }

    public static long step(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), PERIOD_SECONDS);
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param lastUsedStep highest step already accepted for this secret, or {@code null}
     * @return the step the code is valid for, or -1. A step at or below
     *         {@code lastUsedStep} never matches, so a code cannot be replayed.
     */
    static long match(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        byte[] presented = code.getBytes(StandardCharsets.US_ASCII);
        long current = step(now);
        long matched = -1;
        // No early exit: every candidate is computed and compared in constant time.
        for (long candidate = current - WINDOW; candidate <= current + WINDOW; candidate++) {
            boolean equal = MessageDigest.isEqual(presented, code(secret, candidate).getBytes(StandardCharsets.US_ASCII));
            if (equal && (lastUsedStep == null || candidate > lastUsedStep) && matched < 0) {
                matched = candidate;
            }
        }
        return matched;
    }
}
