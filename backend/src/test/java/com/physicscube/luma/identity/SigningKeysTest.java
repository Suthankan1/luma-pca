package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class SigningKeysTest {
    private static final String ISSUER = "https://issuer.example.invalid";
    private static final String AUDIENCE = "luma-api";
    static KeyPair oldPair;
    static KeyPair newPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        oldPair = generator.generateKeyPair();
        newPair = generator.generateKeyPair();
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    static String privatePem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    static String publicPem(KeyPair pair) {
        return pem("PUBLIC KEY", pair.getPublic().getEncoded());
    }

    private static String sign(SigningKeys keys) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(UUID.randomUUID().toString()).id(UUID.randomUUID().toString())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(300)).build();
        return keys.encoder().encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKeyId()).build(), claims)).getTokenValue();
    }

    @Test
    void keyIdIsAStableThumbprintOfTheKey() {
        SigningKeys first = SigningKeys.fromPem(privatePem(newPair), List.of());
        SigningKeys again = SigningKeys.fromPem(privatePem(newPair), List.of());
        assertEquals(first.signingKeyId(), again.signingKeyId());
        assertNotEquals(first.signingKeyId(), SigningKeys.fromPem(privatePem(oldPair), List.of()).signingKeyId());
    }

    @Test
    void tokensFromARetiredKeyStayValidWhileItsPublicKeyIsListed() {
        String oldToken = sign(SigningKeys.fromPem(privatePem(oldPair), List.of()));
        SigningKeys rotated = SigningKeys.fromPem(privatePem(newPair), List.of(publicPem(oldPair)));

        assertEquals(ISSUER, rotated.decoder(ISSUER, AUDIENCE).decode(oldToken).getClaimAsString("iss"));
        assertEquals(ISSUER, rotated.decoder(ISSUER, AUDIENCE).decode(sign(rotated)).getClaimAsString("iss"));
    }

    @Test
    void tokensFromAnUnlistedKeyAreRejected() {
        String oldToken = sign(SigningKeys.fromPem(privatePem(oldPair), List.of()));
        SigningKeys current = SigningKeys.fromPem(privatePem(newPair), List.of());

        assertThrows(JwtException.class, () -> current.decoder(ISSUER, AUDIENCE).decode(oldToken));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedKeySetNeverContainsPrivateParameters() {
        SigningKeys keys = SigningKeys.fromPem(privatePem(newPair), List.of(publicPem(oldPair)));

        List<Map<String, Object>> published = (List<Map<String, Object>>) keys.publicJwks().get("keys");

        assertEquals(2, published.size());
        for (Map<String, Object> key : published) {
            assertEquals("RSA", key.get("kty"));
            assertTrue(key.containsKey("kid") && key.containsKey("n") && key.containsKey("e"));
            for (String privateParameter : List.of("d", "p", "q", "dp", "dq", "qi")) {
                assertFalse(key.containsKey(privateParameter), privateParameter);
            }
        }
        assertEquals(keys.signingKeyId(), published.get(0).get("kid"));
    }

    @Test
    void weakOrMalformedKeysAreRejectedWithoutEchoingKeyMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair weak = generator.generateKeyPair();
        String weakPem = privatePem(weak);

        IllegalArgumentException tooSmall = assertThrows(IllegalArgumentException.class,
                () -> SigningKeys.fromPem(weakPem, List.of()));
        assertFalse(tooSmall.getMessage().contains(weakPem.substring(40, 80)));
        assertThrows(IllegalArgumentException.class, () -> SigningKeys.fromPem(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> SigningKeys.fromPem("not a key", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SigningKeys.fromPem(pem("PRIVATE KEY", new byte[] {1, 2, 3}), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SigningKeys.fromPem(publicPem(newPair), List.of()), "a public key cannot sign");
        assertThrows(IllegalArgumentException.class,
                () -> SigningKeys.fromPem(privatePem(newPair), List.of(publicPem(weak))));
        assertThrows(IllegalArgumentException.class,
                () -> SigningKeys.fromPem(privatePem(newPair), List.of(privatePem(oldPair))),
                "a private key must not be accepted as a verification key");
    }

    @Test
    void ephemeralKeysDifferOnEveryStart() {
        assertNotEquals(SigningKeys.ephemeral().signingKeyId(), SigningKeys.ephemeral().signingKeyId());
    }

    @Test
    void startupFailsWithoutAKeyUnlessEphemeralIsExplicitlyEnabled() {
        TokenConfig config = new TokenConfig();
        AuthProperties none = new AuthProperties(ISSUER, AUDIENCE, java.time.Duration.ofMinutes(10), " ", List.of(),
                false, 5, java.time.Duration.ofMinutes(15),
                java.time.Duration.ofDays(7), java.time.Duration.ofDays(30));
        AuthProperties ephemeral = new AuthProperties(ISSUER, AUDIENCE, java.time.Duration.ofMinutes(10), null, List.of(),
                true, 5, java.time.Duration.ofMinutes(15),
                java.time.Duration.ofDays(7), java.time.Duration.ofDays(30));

        assertThrows(IllegalStateException.class, () -> config.signingKeys(none));
        assertTrue(config.signingKeys(ephemeral).signingKeyId().length() > 20);
    }

    @Test
    void supportsEscapedNewlinesInPemString() {
        String escapedPrivate = privatePem(newPair).replace("\n", "\\n");
        String escapedPublic = publicPem(oldPair).replace("\n", "\\n");

        SigningKeys keys = SigningKeys.fromPem(escapedPrivate, List.of(escapedPublic));
        assertEquals(2, ((List<?>) keys.publicJwks().get("keys")).size());
    }

    @Test
    void accessTokenLifetimeIsBounded() {
        for (long minutes : new long[] {0, 16, 60 * 24}) {
            assertThrows(IllegalArgumentException.class, () -> new AuthProperties(ISSUER, AUDIENCE,
                    java.time.Duration.ofMinutes(minutes), null, List.of(), true, 5, java.time.Duration.ofMinutes(15),
                java.time.Duration.ofDays(7), java.time.Duration.ofDays(30)));
        }
    }
}
