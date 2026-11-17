package com.physicscube.luma.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The RS256 key that signs access tokens plus the public keys that may still verify
 * them. Rotation: deploy a new signing key and list the previous public key as a
 * verification key until its last token has expired. Key ids are RFC 7638
 * thumbprints, so they never need to be configured.
 */
public final class SigningKeys {
    static final int MIN_KEY_BITS = 2048;
    private static final Set<String> REQUIRED_CLAIMS = Set.of("iss", "aud", "sub", "jti", "iat", "nbf", "exp");

    private final RSAKey signingKey;
    private final JWKSet verificationKeys;

    private SigningKeys(RSAKey signingKey, List<JWK> retiredPublicKeys) {
        this.signingKey = signingKey;
        List<JWK> all = new ArrayList<>();
        all.add(signingKey.toPublicJWK());
        all.addAll(retiredPublicKeys);
        this.verificationKeys = new JWKSet(all);
    }

    /** @param signingKeyPem PKCS#8 RSA private key; @param retiredPublicKeysPem X.509 RSA public keys */
    public static SigningKeys fromPem(String signingKeyPem, List<String> retiredPublicKeysPem) {
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            if (!(rsa.generatePrivate(new PKCS8EncodedKeySpec(der(signingKeyPem, "PRIVATE KEY")))
                    instanceof RSAPrivateCrtKey privateKey)) {
                throw new IllegalArgumentException("Signing key must be an RSA private key");
            }
            RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            List<JWK> retired = new ArrayList<>();
            for (String pem : retiredPublicKeysPem) {
                if (pem != null && !pem.isBlank()) {
                    retired.add(jwk((RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(der(pem, "PUBLIC KEY"))), null));
                }
            }
            return new SigningKeys(jwk(publicKey, privateKey), retired);
        } catch (GeneralSecurityException | ClassCastException e) {
            // Deliberately drops the cause's message: it must never echo key material.
            throw new IllegalArgumentException("Signing or verification key is not a valid RSA key in PEM format");
        }
    }

    /** A fresh in-memory key. Tokens stop validating on restart; for development and tests only. */
    public static SigningKeys ephemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS);
            var pair = generator.generateKeyPair();
            return new SigningKeys(jwk((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate()), List.of());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] der(String pem, String type) {
        if (pem == null) {
            throw new IllegalArgumentException("Expected a PEM block of type " + type);
        }
        String normalized = pem.replace("\\n", "\n").replace("\\r", "\r");
        if (!normalized.contains("-----BEGIN " + type + "-----")) {
            throw new IllegalArgumentException("Expected a PEM block of type " + type);
        }
        try {
            return Base64.getMimeDecoder().decode(normalized.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PEM block of type " + type + " is not valid Base64");
        }
    }

    private static RSAKey jwk(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey) {
        if (publicKey.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalArgumentException("RSA keys must be at least " + MIN_KEY_BITS + " bits");
        }
        try {
            RSAKey.Builder builder = new RSAKey.Builder(publicKey).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256);
            if (privateKey != null) {
                builder.privateKey(privateKey);
            }
            return builder.keyIDFromThumbprint().build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String signingKeyId() {
        return signingKey.getKeyID();
    }

    /** For tests in this package that need to craft tokens. */
    RSAKey signingJwk() {
        return signingKey;
    }

    public JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    /** Public keys only, in JWKS form. */
    public Map<String, Object> publicJwks() {
        return verificationKeys.toJSONObject(true);
    }

    /**
     * Accepts only RS256 signatures from a known key id, and requires issuer, audience,
     * subject, token id and the time claims. Symmetric and unsigned tokens cannot
     * match a key and are rejected.
     */
    public JwtDecoder decoder(String issuer, String audience) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(verificationKeys)));
        // Presence is checked on the raw claims here because Spring fills in a missing
        // "iat" before its own validators run.
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, REQUIRED_CLAIMS));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                require(jwt -> jwt.getHeaders().get("kid") instanceof String, "kid header is required"),
                require(jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience), "audience mismatch"),
                require(jwt -> !jwt.getSubject().isBlank() && !jwt.getId().isBlank(), "subject and token id must not be blank")));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> require(java.util.function.Predicate<Jwt> rule, String description) {
        return jwt -> rule.test(jwt) ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
