package com.physicscube.luma.identity;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("luma.auth")
public record AuthProperties(
        String issuer,
        String audience,
        @DefaultValue("PT10M") Duration accessTokenTtl,
        String signingKeyPem,
        @DefaultValue List<String> verificationKeysPem,
        @DefaultValue("false") boolean ephemeralSigningKey,
        @DefaultValue("false") boolean allowElevatedLoginWithoutMfa,
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("PT15M") Duration lockDuration) {

    public AuthProperties {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("luma.auth.issuer and luma.auth.audience are required");
        }
        if (accessTokenTtl.compareTo(Duration.ofMinutes(1)) < 0 || accessTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("luma.auth.access-token-ttl must be between 1 and 15 minutes");
        }
        if (maxFailedLogins < 2 || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("luma.auth lockout settings are invalid");
        }
    }

    /** Never prints key material. */
    @Override
    public String toString() {
        return "AuthProperties[issuer=" + issuer + ", audience=" + audience + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
