package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.AccountStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * A valid signature is not enough: on every request the account must still be
 * active, the token must carry its current auth version and its sign-in session must
 * not be revoked. So logout, disabling an account or changing its password all
 * reject tokens issued earlier. Owner and administrator accounts are additionally
 * accepted only on sessions that passed MFA. Authorities come from the
 * roles stored now, never from the token.
 */
@Component
public class AccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private static final long CACHE_TTL_MS = 15_000;
    private final AccountService accounts;
    private final RefreshSessions sessions;
    private final ConcurrentMap<CacheKey, CacheEntry> authCache = new ConcurrentHashMap<>();

    record CacheKey(UUID accountId, UUID sessionId, long authVersion) {}
    record CacheEntry(AccountSummary account, boolean sessionMfa, long expiresAt) {}

    AccountJwtAuthenticationConverter(AccountService accounts, RefreshSessions sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID accountId;
        UUID sessionId;
        long version;
        try {
            accountId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(String.valueOf(jwt.getClaimAsString(AccessTokenIssuer.SESSION_CLAIM)));
            version = ((Number) jwt.getClaim(AccessTokenIssuer.AUTH_VERSION_CLAIM)).longValue();
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Token is no longer valid");
        }

        CacheKey key = new CacheKey(accountId, sessionId, version);
        long now = System.currentTimeMillis();
        CacheEntry cached = authCache.get(key);
        AccountSummary account;
        Boolean sessionMfa;

        if (cached != null && now < cached.expiresAt()) {
            account = cached.account();
            sessionMfa = cached.sessionMfa();
        } else {
            account = accounts.findById(accountId).orElse(null);
            sessionMfa = account == null ? null : sessions.mfaStateIfActive(sessionId, account.id(), account.authVersion());
            if (account != null && sessionMfa != null && account.status() == AccountStatus.ACTIVE && account.authVersion() == version) {
                if (authCache.size() > 5000) {
                    authCache.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
                }
                authCache.put(key, new CacheEntry(account, sessionMfa, now + CACHE_TTL_MS));
            }
        }

        if (account == null || sessionMfa == null || account.status() != AccountStatus.ACTIVE
                || !account.academyId().toString().equals(jwt.getClaimAsString(AccessTokenIssuer.ACADEMY_CLAIM))
                // An account promoted to owner/admin after signing in without MFA must sign in again.
                || (AuthService.isElevated(account.roles()) && !sessionMfa)) {
            authCache.remove(key);
            throw new InvalidBearerTokenException("Token is no longer valid");
        }
        List<SimpleGrantedAuthority> authorities = account.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        return new JwtAuthenticationToken(jwt, authorities, account.id().toString());
    }
}
