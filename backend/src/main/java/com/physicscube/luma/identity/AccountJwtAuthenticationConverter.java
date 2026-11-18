package com.physicscube.luma.identity;

import com.physicscube.luma.identity.domain.AccountStatus;
import java.util.List;
import java.util.UUID;
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
    private final AccountService accounts;
    private final RefreshSessions sessions;

    AccountJwtAuthenticationConverter(AccountService accounts, RefreshSessions sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    public static void evictAccount(UUID accountId) {
        // No-op hook preserved for compatibility
    }

    public void evict(UUID accountId) {
        // No-op hook preserved for compatibility
    }

    public void evictAll() {
        // No-op hook preserved for compatibility
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        RefreshSessions.RequestAuth auth;
        UUID accountId;
        try {
            accountId = UUID.fromString(jwt.getSubject());
            // One round trip: account, roles and session together (the database may be far away).
            auth = sessions.requestAuth(UUID.fromString(String.valueOf(jwt.getClaimAsString(AccessTokenIssuer.SESSION_CLAIM))), accountId);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Token is no longer valid");
        }
        if (auth == null || auth.status() != AccountStatus.ACTIVE
                || !(jwt.getClaim(AccessTokenIssuer.AUTH_VERSION_CLAIM) instanceof Number version)
                || version.longValue() != auth.authVersion()
                || !auth.academyId().toString().equals(jwt.getClaimAsString(AccessTokenIssuer.ACADEMY_CLAIM))
                // An account promoted to owner/admin after signing in without MFA must sign in again.
                || (AuthService.isElevated(auth.roles()) && !auth.mfaVerified())) {
            throw new InvalidBearerTokenException("Token is no longer valid");
        }
        List<SimpleGrantedAuthority> authorities = auth.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        return new JwtAuthenticationToken(jwt, authorities, accountId.toString());
    }
}
