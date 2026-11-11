package com.physicscube.luma.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs short-lived access tokens. Claims identify the account only: no email, name
 * or role list, because roles and scope are read from the database on every request.
 */
@Component
class AccessTokenIssuer {
    static final String ACADEMY_CLAIM = "academy_id";
    static final String AUTH_VERSION_CLAIM = "auth_version";
    static final String SESSION_CLAIM = "sid";

    private final JwtEncoder encoder;
    private final SigningKeys keys;
    private final AuthProperties properties;

    AccessTokenIssuer(JwtEncoder encoder, SigningKeys keys, AuthProperties properties) {
        this.encoder = encoder;
        this.keys = keys;
        this.properties = properties;
    }

    AccessToken issue(AccountSummary account, UUID sessionId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(account.id().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(ACADEMY_CLAIM, account.academyId().toString())
                .claim(AUTH_VERSION_CLAIM, account.authVersion())
                .claim(SESSION_CLAIM, sessionId.toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKeyId()).build();
        return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
                properties.accessTokenTtl().toSeconds());
    }
}
