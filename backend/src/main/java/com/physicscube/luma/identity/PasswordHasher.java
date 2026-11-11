package com.physicscube.luma.identity;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id with a random 16-byte salt per hash, 19 MiB memory, 2 iterations and
 * parallelism 1 (OWASP baseline). Parameters are stored in each hash, so they can
 * be raised later and old hashes detected with {@link #needsRehash(String)}.
 */
@Component
public class PasswordHasher {
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedHash) {
        return rawPassword != null && encodedHash != null && encoder.matches(rawPassword, encodedHash);
    }

    public boolean needsRehash(String encodedHash) {
        return encoder.upgradeEncoding(encodedHash);
    }
}
