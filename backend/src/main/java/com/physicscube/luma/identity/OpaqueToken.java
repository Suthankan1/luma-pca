package com.physicscube.luma.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 256-bit random bearer secrets (invitations, refresh tokens) and the SHA-256 digest
 * that is stored instead of them. With that much entropy a stolen digest cannot be
 * brute-forced, so no keyed hash is needed.
 */
final class OpaqueToken {
    static final int MAX_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueToken() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean plausible(String token) {
        return token != null && !token.isEmpty() && token.length() <= MAX_LENGTH;
    }

    static String digest(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
