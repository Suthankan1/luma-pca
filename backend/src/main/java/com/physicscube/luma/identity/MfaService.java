package com.physicscube.luma.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Second factor for sign-in: an authenticator-app code, or a single-use recovery
 * code. The password step hands out a short-lived challenge; this service turns a
 * challenge plus a correct code into permission to start a session.
 *
 * <p>Enrollment, verification, session creation and reset share the account row
 * lock. Expected failures are returned to the caller so failure counts can commit.
 */
@Component
class MfaService {
    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final int RECOVERY_CODES = 10;
    private static final String ISSUER = "LUMA";
    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    record Challenge(String token, long expiresInSeconds, boolean enrolled) {
    }

    record Enrollment(String secret, String otpauthUri) {
    }

    /** @param recoveryCodes present only when this verification completed enrolment */
    record Verified(UUID accountId, List<String> recoveryCodes) {
    }

    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuthProperties properties;

    MfaService(JdbcTemplate jdbc, SecretCipher cipher, AuthProperties properties) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    boolean isEnrolled(UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM account_totp WHERE account_id = ? AND confirmed_at IS NOT NULL)",
                Boolean.class, accountId));
    }

    /** Called with the account locked, after the password was verified. */
    @Transactional
    Challenge begin(UUID accountId, int authVersion) {
        Instant now = Instant.now();
        jdbc.update("DELETE FROM mfa_challenges WHERE account_id = ? AND (consumed_at IS NOT NULL OR expires_at <= ?)",
                accountId, Timestamp.from(now));
        String token = OpaqueToken.generate();
        jdbc.update("INSERT INTO mfa_challenges (id, account_id, token_hash, auth_version, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), accountId, OpaqueToken.digest(token), authVersion,
                Timestamp.from(now), Timestamp.from(now.plus(CHALLENGE_TTL)));
        return new Challenge(token, CHALLENGE_TTL.toSeconds(), isEnrolled(accountId));
    }

    /**
     * Creates (or replaces) the not-yet-confirmed authenticator secret.
     *
     * @return {@code null} if the challenge is unusable or the account already has a confirmed authenticator
     */
    @Transactional
    Enrollment enroll(String challengeToken) {
        Map<String, Object> challenge = lockChallenge(challengeToken);
        if (challenge == null) {
            return null;
        }
        UUID accountId = (UUID) challenge.get("account_id");
        List<Timestamp> existing = jdbc.query("SELECT confirmed_at FROM account_totp WHERE account_id = ?",
                (row, index) -> row.getTimestamp(1), accountId);
        if (!existing.isEmpty() && existing.get(0) != null) {
            return null;
        }
        byte[] secret = new byte[Totp.SECRET_BYTES];
        random.nextBytes(secret);
        jdbc.update("""
                INSERT INTO account_totp (account_id, secret_ciphertext, created_at) VALUES (?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE
                  SET secret_ciphertext = EXCLUDED.secret_ciphertext, created_at = EXCLUDED.created_at, last_used_step = NULL
                """, accountId, cipher.encrypt(secret, accountId), Timestamp.from(Instant.now()));
        String encoded = Base32.encode(secret);
        String label = URLEncoder.encode(ISSUER + ":" + challenge.get("email"), StandardCharsets.UTF_8).replace("+", "%20");
        return new Enrollment(encoded, "otpauth://totp/" + label + "?secret=" + encoded + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=6&period=" + Totp.PERIOD_SECONDS);
    }

    /** @return {@code null} if verification failed for any reason */
    @Transactional
    Verified verify(String challengeToken, String code) {
        Map<String, Object> challenge = lockChallenge(challengeToken);
        if (challenge == null) {
            return null;
        }
        UUID accountId = (UUID) challenge.get("account_id");
        Instant now = Instant.now();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT secret_ciphertext, confirmed_at, last_used_step, failed_count FROM account_totp "
                        + "WHERE account_id = ?", accountId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> totp = rows.get(0);
        boolean confirmed = totp.get("confirmed_at") != null;
        String presented = code == null ? "" : code.strip();
        List<String> recoveryCodes = null;
        boolean accepted;
        if (presented.matches("\\d{6}")) {
            long step = Totp.match(cipher.decrypt((byte[]) totp.get("secret_ciphertext"), accountId), presented, now,
                    (Long) totp.get("last_used_step"));
            accepted = step >= 0;
            if (accepted) {
                jdbc.update("UPDATE account_totp SET last_used_step = ?, failed_count = 0, "
                        + "confirmed_at = COALESCE(confirmed_at, ?) WHERE account_id = ?", step, Timestamp.from(now), accountId);
                if (!confirmed) {
                    recoveryCodes = issueRecoveryCodes(accountId, now);
                }
            }
        } else {
            accepted = confirmed && redeemRecoveryCode(accountId, presented, now);
            if (accepted) {
                jdbc.update("UPDATE account_totp SET failed_count = 0 WHERE account_id = ?", accountId);
            }
        }
        if (!accepted) {
            recordFailure(accountId, ((Number) totp.get("failed_count")).intValue() + 1, now);
            return null;
        }
        if (jdbc.update("UPDATE mfa_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL",
                Timestamp.from(now), challenge.get("id")) != 1) {
            throw new IllegalStateException("Locked MFA challenge changed during verification");
        }
        return new Verified(accountId, recoveryCodes);
    }

    /** Clears the authenticator, recovery codes and pending challenges while the account is locked. */
    @Transactional
    void clear(UUID accountId) {
        jdbc.update("DELETE FROM account_totp WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM account_recovery_codes WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM mfa_challenges WHERE account_id = ?", accountId);
    }

    private Map<String, Object> lockChallenge(String token) {
        if (!OpaqueToken.plausible(token)) {
            return null;
        }
        String digest = OpaqueToken.digest(token);
        Map<String, Object> candidate = usableChallenge(digest, Instant.now());
        if (candidate == null) {
            return null;
        }
        jdbc.queryForList("SELECT id FROM accounts WHERE id = ? FOR UPDATE", candidate.get("account_id"));
        // A concurrent transaction may have changed the challenge or account while we waited.
        return usableChallenge(digest, Instant.now());
    }

    private Map<String, Object> usableChallenge(String digest, Instant now) {
        Timestamp at = Timestamp.from(now);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id, c.account_id, a.email FROM mfa_challenges c JOIN accounts a ON a.id = c.account_id
                WHERE c.token_hash = ? AND c.consumed_at IS NULL AND c.expires_at > ?
                  AND a.status = 'ACTIVE' AND a.auth_version = c.auth_version
                  AND (a.locked_until IS NULL OR a.locked_until <= ?)
                """, digest, at, at);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void recordFailure(UUID accountId, int failures, Instant now) {
        if (failures >= properties.maxFailedLogins()) {
            jdbc.update("UPDATE account_totp SET failed_count = 0 WHERE account_id = ?", accountId);
            jdbc.update("UPDATE accounts SET locked_until = ? WHERE id = ?",
                    Timestamp.from(now.plus(properties.lockDuration())), accountId);
            log.warn("Account {} locked after repeated failed MFA attempts", accountId);
        } else {
            jdbc.update("UPDATE account_totp SET failed_count = ? WHERE account_id = ?", failures, accountId);
        }
    }

    private List<String> issueRecoveryCodes(UUID accountId, Instant now) {
        jdbc.update("DELETE FROM account_recovery_codes WHERE account_id = ?", accountId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] bytes = new byte[10];
            random.nextBytes(bytes);
            String raw = Base32.encode(bytes);
            codes.add(raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12));
            jdbc.update("INSERT INTO account_recovery_codes (id, account_id, code_hash, created_at) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), accountId, OpaqueToken.digest(raw), Timestamp.from(now));
        }
        return codes;
    }

    /** 80 random bits per code, so a stolen digest cannot be brute-forced. */
    private boolean redeemRecoveryCode(UUID accountId, String presented, Instant now) {
        String normalized = presented.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z2-7]{16}") && jdbc.update(
                "UPDATE account_recovery_codes SET used_at = ? WHERE account_id = ? AND code_hash = ? AND used_at IS NULL",
                Timestamp.from(now), accountId, OpaqueToken.digest(normalized)) == 1;
    }
}
