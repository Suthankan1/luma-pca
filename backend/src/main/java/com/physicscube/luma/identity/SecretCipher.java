package com.physicscube.luma.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for authenticator secrets at rest. Each ciphertext is bound to its
 * account id, so a row copied onto another account does not decrypt.
 */
public final class SecretCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("MFA encryption key must be exactly 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** @param encoded 32 bytes in standard or URL-safe Base64 */
    public static SecretCipher fromBase64(String encoded) {
        try {
            String standard = encoded.strip().replace('-', '+').replace('_', '/');
            return new SecretCipher(Base64.getDecoder().decode(standard));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MFA encryption key must be 32 bytes of Base64");
        }
    }

    public static SecretCipher ephemeral() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretCipher(key);
    }

    public byte[] encrypt(byte[] plaintext, UUID accountId) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(accountId.toString().getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.allocate(NONCE_BYTES + plaintext.length + TAG_BITS / 8)
                    .put(nonce).put(cipher.doFinal(plaintext)).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalStateException if the data was altered, belongs to another account or was written with another key */
    public byte[] decrypt(byte[] stored, UUID accountId) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, NONCE_BYTES));
            cipher.updateAAD(accountId.toString().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(Arrays.copyOfRange(stored, NONCE_BYTES, stored.length));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Stored MFA secret cannot be decrypted");
        }
    }
}
