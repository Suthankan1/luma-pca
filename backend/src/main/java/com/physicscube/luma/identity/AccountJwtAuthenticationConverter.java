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
 * active and the token must carry its current auth version, so disabling an account
 * or changing its password rejects tokens issued earlier. Authorities come from the
 * roles stored now, never from the token.
 */
@Component
public class AccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final AccountService accounts;

    AccountJwtAuthenticationConverter(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AccountSummary account;
        try {
            account = accounts.findById(UUID.fromString(jwt.getSubject())).orElse(null);
        } catch (IllegalArgumentException e) {
            account = null;
        }
        if (account == null || account.status() != AccountStatus.ACTIVE
                || !(jwt.getClaim(AccessTokenIssuer.AUTH_VERSION_CLAIM) instanceof Number version)
                || version.longValue() != account.authVersion()
                || !account.academyId().toString().equals(jwt.getClaimAsString(AccessTokenIssuer.ACADEMY_CLAIM))) {
            throw new InvalidBearerTokenException("Token is no longer valid");
        }
        List<SimpleGrantedAuthority> authorities = account.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        return new JwtAuthenticationToken(jwt, authorities, account.id().toString());
    }
}
