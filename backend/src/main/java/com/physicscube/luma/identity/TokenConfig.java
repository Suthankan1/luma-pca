package com.physicscube.luma.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class TokenConfig {
    private static final Logger log = LoggerFactory.getLogger(TokenConfig.class);

    @Bean
    SigningKeys signingKeys(AuthProperties properties) {
        boolean configured = properties.signingKeyPem() != null && !properties.signingKeyPem().isBlank();
        if (configured) {
            return SigningKeys.fromPem(properties.signingKeyPem(), properties.verificationKeysPem());
        }
        if (!properties.ephemeralSigningKey()) {
            throw new IllegalStateException("No access-token signing key: set LUMA_AUTH_SIGNING_KEY_PEM, "
                    + "or LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true for local development only");
        }
        log.warn("Using an EPHEMERAL access-token signing key. Not for production: every restart invalidates all tokens.");
        return SigningKeys.ephemeral();
    }

    @Bean
    SecretCipher mfaSecretCipher(AuthProperties properties) {
        if (properties.mfaEncryptionKey() != null && !properties.mfaEncryptionKey().isBlank()) {
            return SecretCipher.fromBase64(properties.mfaEncryptionKey());
        }
        if (!properties.ephemeralSigningKey()) {
            throw new IllegalStateException("No MFA encryption key: set LUMA_AUTH_MFA_ENCRYPTION_KEY, "
                    + "or LUMA_AUTH_EPHEMERAL_SIGNING_KEY=true for local development only");
        }
        log.warn("Using an EPHEMERAL MFA encryption key. Not for production: "
                + "authenticators enrolled now stop working after a restart.");
        return SecretCipher.ephemeral();
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        return keys.encoder();
    }

    @Bean
    JwtDecoder jwtDecoder(SigningKeys keys, AuthProperties properties) {
        return keys.decoder(properties.issuer(), properties.audience());
    }
}
