package com.physicscube.luma.zoom;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Zoom's documented webhook integrity check: HMAC-SHA256, keyed with the app's
 * webhook secret token, over {@code v0:<x-zm-request-timestamp>:<raw body>}, sent as
 * {@code x-zm-signature: v0=<lowercase hex>}. Endpoint URL validation answers with
 * the hex HMAC of the plainToken under the same key.
 */
public final class ZoomWebhookSignature {
    public enum Result { VALID, MISSING, MALFORMED, STALE, MISMATCH }

    private final byte[] key;

    public ZoomWebhookSignature(String secretToken) {
        this.key = secretToken.strip().getBytes(StandardCharsets.UTF_8);
    }

    public Result verify(String timestampHeader, String signatureHeader, byte[] body, Instant now, Duration maxSkew) {
        if (timestampHeader == null || signatureHeader == null) {
            return Result.MISSING;
        }
        if (!timestampHeader.matches("\\d{1,12}") || !signatureHeader.matches("v0=[0-9a-f]{64}")) {
            return Result.MALFORMED;
        }
        long seconds = Long.parseLong(timestampHeader);
        if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(maxSkew) > 0) {
            return Result.STALE;
        }
        byte[] prefix = ("v0:" + timestampHeader + ":").getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        byte[] expected = ("v0=" + hmacHex(message)).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signatureHeader.getBytes(StandardCharsets.US_ASCII)) ? Result.VALID : Result.MISMATCH;
    }

    /** The {@code encryptedToken} for an {@code endpoint.url_validation} request. */
    public String validationToken(String plainToken) {
        return hmacHex(plainToken.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
