package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
    private static final byte[] PLAIN = "twenty-byte-secret!!".getBytes();
    private final UUID account = UUID.randomUUID();
    private final SecretCipher cipher = SecretCipher.ephemeral();

    @Test
    void roundTripsAndNeverStoresThePlaintext() {
        byte[] stored = cipher.encrypt(PLAIN, account);

        assertArrayEquals(PLAIN, cipher.decrypt(stored, account));
        assertEquals(12 + PLAIN.length + 16, stored.length);
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains(new String(PLAIN)));
        assertFalse(Arrays.equals(stored, cipher.encrypt(PLAIN, account)), "a fresh nonce every time");
    }

    @Test
    void ciphertextIsBoundToItsAccountAndKeyAndDetectsTampering() {
        byte[] stored = cipher.encrypt(PLAIN, account);
        byte[] tampered = stored.clone();
        tampered[stored.length - 1] ^= 1;

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(stored, UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> SecretCipher.ephemeral().decrypt(stored, account));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered, account));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(new byte[5], account));
    }

    @Test
    void keyMustBeExactly256Bits() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        UUID id = UUID.randomUUID();

        SecretCipher standard = SecretCipher.fromBase64(Base64.getEncoder().encodeToString(key));
        SecretCipher urlSafe = SecretCipher.fromBase64(Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        assertArrayEquals(PLAIN, urlSafe.decrypt(standard.encrypt(PLAIN, id), id));

        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(null));
        assertThrows(IllegalArgumentException.class,
                () -> SecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[31])));
        assertThrows(IllegalArgumentException.class, () -> SecretCipher.fromBase64("not base64 !!"));
    }

    @Test
    void startupFailsWithoutAnMfaKeyUnlessEphemeralKeysAreExplicitlyEnabled() {
        TokenConfig config = new TokenConfig();
        java.time.Duration ten = java.time.Duration.ofMinutes(10);
        java.util.function.BiFunction<Boolean, String, AuthProperties> properties = (ephemeral, key) -> new AuthProperties(
                "https://issuer.example.invalid", "luma-api", ten, null, java.util.List.of(), ephemeral, key, 5,
                java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(7), java.time.Duration.ofDays(30));
        byte[] key = new byte[32];
        UUID id = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> config.mfaSecretCipher(properties.apply(false, " ")));
        assertThrows(IllegalArgumentException.class, () -> config.mfaSecretCipher(properties.apply(true, "too-short")));
        SecretCipher configured = config.mfaSecretCipher(properties.apply(false, Base64.getEncoder().encodeToString(key)));
        assertArrayEquals(PLAIN, new SecretCipher(key).decrypt(configured.encrypt(PLAIN, id), id));
        SecretCipher throwaway = config.mfaSecretCipher(properties.apply(true, null));
        assertArrayEquals(PLAIN, throwaway.decrypt(throwaway.encrypt(PLAIN, id), id));
        assertFalse(properties.apply(false, "c2VjcmV0").toString().contains("c2VjcmV0"), "the key must never be printed");
    }
}
