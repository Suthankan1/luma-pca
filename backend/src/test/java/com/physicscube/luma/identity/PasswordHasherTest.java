package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHasherTest {
    private static final String PASSWORD = "quiet river under bridge";
    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void producesArgon2idHashWithTheConfiguredCost() {
        String hash = hasher.hash(PASSWORD);
        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), hash);
        assertFalse(hash.contains(PASSWORD));
        assertTrue(hash.length() <= 255);
    }

    @Test
    void saltsEveryHash() {
        assertNotEquals(hasher.hash(PASSWORD), hasher.hash(PASSWORD));
    }

    @Test
    void verifiesOnlyTheOriginalPassword() {
        String hash = hasher.hash(PASSWORD);
        assertTrue(hasher.matches(PASSWORD, hash));
        assertFalse(hasher.matches(PASSWORD + " ", hash));
        assertFalse(hasher.matches(PASSWORD.toUpperCase(), hash));
        assertFalse(hasher.matches("", hash));
    }

    @Test
    void missingOrForeignHashesNeverMatch() {
        assertFalse(hasher.matches(PASSWORD, null));
        assertFalse(hasher.matches(null, hasher.hash(PASSWORD)));
        assertFalse(hasher.matches(PASSWORD, PASSWORD));
        assertFalse(hasher.matches(PASSWORD, new BCryptPasswordEncoder(4).encode(PASSWORD)));
    }

    @Test
    void currentHashesDoNotNeedRehashing() {
        assertFalse(hasher.needsRehash(hasher.hash(PASSWORD)));
    }
}
