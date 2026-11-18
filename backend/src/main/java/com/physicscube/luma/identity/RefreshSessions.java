package com.physicscube.luma.identity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side refresh sessions. Every operation on a session first locks its row, so
 * concurrent refreshes of one token are processed one after another: the first
 * rotates it and any later presentation is detected as reuse and revokes the whole
 * session, including the token the first caller just received.
 *
 * <p>Methods report invalid tokens through their return value instead of throwing,
 * so a revocation made while rejecting a token is committed.
 */
@Component
class RefreshSessions {
    private static final Logger log = LoggerFactory.getLogger(RefreshSessions.class);

    record Issued(UUID sessionId, UUID accountId, String refreshToken, long refreshExpiresInSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final AuthProperties properties;

    RefreshSessions(JdbcTemplate jdbc, AuthProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    Issued start(UUID accountId, int authVersion, boolean mfaVerified) {
        Instant now = Instant.now();
        UUID sessionId = UUID.randomUUID();
        Instant sessionExpiry = now.plus(properties.sessionMaxLifetime());
        jdbc.update("INSERT INTO refresh_sessions (id, account_id, auth_version, created_at, expires_at, mfa_verified) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, accountId, authVersion, Timestamp.from(now), Timestamp.from(sessionExpiry), mfaVerified);
        return issue(sessionId, accountId, now, sessionExpiry);
    }

    /** @return the replacement token, or {@code null} if the presented one is not usable */
    @Transactional
    Issued rotate(String presentedToken) {
        if (!OpaqueToken.plausible(presentedToken)) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id AS token_id, t.expires_at AS token_expires_at, s.id AS session_id, s.account_id,
                       s.auth_version, s.expires_at AS session_expires_at, s.revoked_at, s.mfa_verified
                FROM refresh_tokens t JOIN refresh_sessions s ON s.id = t.session_id
                WHERE t.token_hash = ? FOR UPDATE OF s
                """, OpaqueToken.digest(presentedToken));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        UUID sessionId = (UUID) row.get("session_id");
        UUID accountId = (UUID) row.get("account_id");
        Instant now = Instant.now();
        Instant sessionExpiry = ((Timestamp) row.get("session_expires_at")).toInstant();
        if (row.get("revoked_at") != null || !sessionExpiry.isAfter(now)
                || !((Timestamp) row.get("token_expires_at")).toInstant().isAfter(now)) {
            return null;
        }
        // The session lock is held, so this conditional update is the single-use authority.
        if (jdbc.update("UPDATE refresh_tokens SET used_at = ? WHERE id = ? AND used_at IS NULL",
                Timestamp.from(now), row.get("token_id")) != 1) {
            revoke(sessionId, "REUSE_DETECTED", now);
            log.warn("Refresh token reuse detected; session {} of account {} revoked", sessionId, accountId);
            return null;
        }
        // Like bearer authentication, refreshing cannot continue a password-only
        // session once its account has been promoted to an elevated role.
        Integer currentVersion = jdbc.query("""
                SELECT auth_version FROM accounts
                WHERE id = ? AND status = 'ACTIVE' AND (? OR NOT EXISTS (
                    SELECT 1 FROM account_roles WHERE account_id = accounts.id
                      AND role IN ('ACADEMY_OWNER', 'ACADEMY_ADMIN')
                ))
                """, results -> results.next() ? results.getInt(1) : null, accountId, row.get("mfa_verified"));
        if (currentVersion == null || currentVersion != ((Number) row.get("auth_version")).intValue()) {
            revoke(sessionId, "ACCOUNT_CHANGED", now);
            return null;
        }
        return issue(sessionId, accountId, now, sessionExpiry);
    }

    /** Ends the session the token belongs to, whether or not the token is still current. */
    @Transactional
    void revokeByToken(String presentedToken) {
        if (OpaqueToken.plausible(presentedToken)) {
            UUID accountId = jdbc.query("""
                    SELECT account_id FROM refresh_sessions
                    WHERE id = (SELECT session_id FROM refresh_tokens WHERE token_hash = ?)
                    """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, OpaqueToken.digest(presentedToken));
            jdbc.update("""
                    UPDATE refresh_sessions SET revoked_at = ?, revoke_reason = 'LOGOUT'
                    WHERE revoked_at IS NULL AND id = (SELECT session_id FROM refresh_tokens WHERE token_hash = ?)
                    """, Timestamp.from(Instant.now()), OpaqueToken.digest(presentedToken));
            if (accountId != null) {
                AccountJwtAuthenticationConverter.evictAccount(accountId);
            }
        }
    }

    @Transactional
    int revokeAll(UUID accountId) {
        int updated = jdbc.update("UPDATE refresh_sessions SET revoked_at = ?, revoke_reason = 'LOGOUT_ALL' "
                + "WHERE account_id = ? AND revoked_at IS NULL", Timestamp.from(Instant.now()), accountId);
        AccountJwtAuthenticationConverter.evictAccount(accountId);
        return updated;
    }

    /** @return whether the current account's live session passed MFA, or {@code null} when the session is invalid */
    @Transactional(readOnly = true)
    /** What every API request needs to know about its token's account and session, read in one database round trip. */
    record RequestAuth(UUID academyId, com.physicscube.luma.identity.domain.AccountStatus status, int authVersion,
                       java.util.Set<com.physicscube.luma.identity.domain.Role> roles, boolean mfaVerified) {
    }

    /**
     * The account and its session together, or {@code null} when the account is gone or the session is revoked, expired or
     * from an older auth version. Same rules as {@link AccountService#findById} plus {@link #mfaStateIfActive}, in one query.
     */
    RequestAuth requestAuth(UUID sessionId, UUID accountId) {
        return jdbc.query("""
                SELECT a.academy_id, a.status, a.auth_version, s.mfa_verified,
                       ARRAY(SELECT r.role FROM account_roles r WHERE r.account_id = a.id) AS roles
                FROM accounts a
                JOIN refresh_sessions s ON s.account_id = a.id AND s.id = ? AND s.auth_version = a.auth_version
                                       AND s.revoked_at IS NULL AND s.expires_at > ?
                WHERE a.id = ?
                """, results -> {
            if (!results.next()) {
                return null;
            }
            java.util.Set<com.physicscube.luma.identity.domain.Role> roles = java.util.EnumSet.noneOf(com.physicscube.luma.identity.domain.Role.class);
            for (String role : (String[]) results.getArray("roles").getArray()) {
                roles.add(com.physicscube.luma.identity.domain.Role.valueOf(role));
            }
            return new RequestAuth((UUID) results.getObject("academy_id"),
                    com.physicscube.luma.identity.domain.AccountStatus.valueOf(results.getString("status")),
                    results.getInt("auth_version"), roles, results.getBoolean("mfa_verified"));
        }, sessionId, Timestamp.from(Instant.now()), accountId);
    }

    Boolean mfaStateIfActive(UUID sessionId, UUID accountId, int authVersion) {
        return jdbc.query("SELECT mfa_verified FROM refresh_sessions "
                        + "WHERE id = ? AND account_id = ? AND auth_version = ? AND revoked_at IS NULL AND expires_at > ?",
                results -> results.next() ? results.getBoolean(1) : null,
                sessionId, accountId, authVersion, Timestamp.from(Instant.now()));
    }

    private void revoke(UUID sessionId, String reason, Instant now) {
        jdbc.update("UPDATE refresh_sessions SET revoked_at = ?, revoke_reason = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(now), reason, sessionId);
    }

    private Issued issue(UUID sessionId, UUID accountId, Instant now, Instant sessionExpiry) {
        Instant idleExpiry = now.plus(properties.refreshTokenTtl());
        Instant expiry = idleExpiry.isBefore(sessionExpiry) ? idleExpiry : sessionExpiry;
        String token = OpaqueToken.generate();
        jdbc.update("INSERT INTO refresh_tokens (id, session_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, OpaqueToken.digest(token), Timestamp.from(now), Timestamp.from(expiry));
        return new Issued(sessionId, accountId, token, Math.max(1, Duration.between(now, expiry).toSeconds()));
    }
}
