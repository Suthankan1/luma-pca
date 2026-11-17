package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.support.HttpApi;
import com.physicscube.luma.support.PostgresIntegrationTest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class AuthHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSPHRASE = "quiet river under bridge";
    private static final String WRONG = "not the right passphrase";
    private static final String EMAIL = "student@example.invalid";

    @Autowired
    AccountService accounts;
    @Autowired
    InvitationService invitations;
    @Autowired
    SigningKeys keys;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    AuthProperties properties;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${local.server.port}")
    int port;

    HttpApi api;
    UUID academyId;
    UUID studentId;

    @BeforeEach
    void seed() {
        api = new HttpApi(port);
        resetDatabase();
        academyId = UUID.randomUUID();
        jdbc.update("INSERT INTO academies (id, name) VALUES (?, 'Test Academy')", academyId);
        studentId = accounts.register(academyId, EMAIL, Set.of(Role.STUDENT)).id();
        accounts.setPassword(studentId, PASSPHRASE);
    }

    private HttpResponse<String> login(String email, String passphrase) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", passphrase);
        return api.post("/api/v1/auth/login", HttpApi.json(body));
    }

    private String accessToken() {
        HttpResponse<String> response = login(EMAIL, PASSPHRASE);
        assertEquals(200, response.statusCode(), response.body());
        return (String) HttpApi.parse(response).get("accessToken");
    }

    private Map<String, Object> account() {
        return jdbc.queryForMap("SELECT * FROM accounts WHERE id = ?", studentId);
    }

    private int me(String token) {
        return api.get("/api/v1/auth/me", token).statusCode();
    }

    /** A token signed with the real key whose claims start valid and are then altered. */
    private String forged(JWSSigner signer, JWSHeader header, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        String sessionId = decoder.decode(accessToken()).getClaimAsString("sid");
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().claim("sid", sessionId)
                .issuer(properties.issuer()).audience(properties.audience()).subject(studentId.toString())
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("academy_id", academyId.toString()).claim("auth_version", account().get("auth_version"));
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(header, claims.build());
        jwt.sign(signer);
        return jwt.serialize();
    }

    private String forged(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        return forged(new RSASSASigner(keys.signingJwk()),
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.signingKeyId()).build(), change);
    }

    @Test
    void loginReturnsShortLivedRs256TokenWithRequiredClaimsAndNoPersonalData() {
        HttpResponse<String> response = login("  Student@Example.INVALID ", PASSPHRASE);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        Map<String, Object> body = HttpApi.parse(response);
        assertEquals(Set.of("accessToken", "tokenType", "expiresIn", "refreshToken", "refreshExpiresIn"), body.keySet());
        assertEquals("Bearer", body.get("tokenType"));
        assertEquals(600, ((Number) body.get("expiresIn")).intValue());

        Jwt jwt = decoder.decode((String) body.get("accessToken"));
        assertEquals("RS256", jwt.getHeaders().get("alg"));
        assertEquals(keys.signingKeyId(), jwt.getHeaders().get("kid"));
        assertEquals(properties.issuer(), jwt.getClaimAsString("iss"));
        assertEquals(List.of(properties.audience()), jwt.getAudience());
        assertEquals(studentId.toString(), jwt.getSubject());
        assertNotNull(UUID.fromString(jwt.getId()));
        assertEquals(jwt.getIssuedAt(), jwt.getNotBefore());
        assertEquals(600, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
        assertEquals(Set.of("iss", "aud", "sub", "jti", "iat", "nbf", "exp", "academy_id", "auth_version", "sid"),
                jwt.getClaims().keySet());
        assertFalse(jwt.getTokenValue().contains(EMAIL));
        assertEquals(0, account().get("failed_login_count"));
    }

    @Test
    void everyTokenHasItsOwnId() {
        assertNotEquals(decoder.decode(accessToken()).getId(), decoder.decode(accessToken()).getId());
    }

    @Test
    void allCredentialFailuresLookIdentical() {
        UUID pendingId = accounts.register(academyId, "pending@example.invalid", Set.of(Role.STUDENT)).id();
        UUID disabledId = accounts.register(academyId, "disabled@example.invalid", Set.of(Role.STUDENT)).id();
        accounts.setPassword(disabledId, PASSPHRASE);
        accounts.disable(disabledId);

        List<HttpResponse<String>> failures = List.of(
                login(EMAIL, WRONG),
                login("nobody@example.invalid", PASSPHRASE),
                login("pending@example.invalid", PASSPHRASE),
                login("disabled@example.invalid", PASSPHRASE),
                login("not-an-email", PASSPHRASE));

        for (HttpResponse<String> failure : failures) {
            assertEquals(401, failure.statusCode());
            assertEquals(failures.get(0).body(), failure.body());
            assertFalse(failure.body().contains("accessToken"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT failed_login_count FROM accounts WHERE id = ?", Integer.class, pendingId));
    }

    @Test
    void malformedLoginRequestsAreRejectedBeforeAnyPasswordCheck() {
        Map<String, String> missing = Map.of("email", EMAIL);
        assertEquals(400, api.post("/api/v1/auth/login", HttpApi.json(missing)).statusCode());
        assertEquals(400, api.post("/api/v1/auth/login", "{not json").statusCode());
        assertEquals(400, api.post("/api/v1/auth/login", "").statusCode());
        assertEquals(400, login(EMAIL, "x".repeat(257)).statusCode());
        assertEquals(400, login("a".repeat(250) + "@example.invalid", PASSPHRASE).statusCode());
        assertEquals(0, account().get("failed_login_count"));
    }

    @Test
    void accountLocksAfterFiveFailuresAndEvenTheRightPasswordIsRefused() {
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login(EMAIL, WRONG).statusCode());
        }
        assertEquals(5, account().get("failed_login_count"));
        assertNotNull(account().get("locked_until"));

        HttpResponse<String> whileLocked = login(EMAIL, PASSPHRASE);

        assertEquals(401, whileLocked.statusCode());
        assertEquals(login("nobody@example.invalid", PASSPHRASE).body(), whileLocked.body());
        assertEquals(5, account().get("failed_login_count"), "attempts while locked are not counted");
    }

    @Test
    void lockExpiresAndASuccessfulLoginClearsTheCounter() {
        for (int i = 0; i < 5; i++) {
            login(EMAIL, WRONG);
        }
        jdbc.update("UPDATE accounts SET locked_until = NOW() - INTERVAL '1 second' WHERE id = ?", studentId);

        assertEquals(401, login(EMAIL, WRONG).statusCode());
        assertEquals(1, account().get("failed_login_count"), "a new count starts after the lock expires");
        assertNull(account().get("locked_until"));
        assertEquals(200, login(EMAIL, PASSPHRASE).statusCode());
        assertEquals(0, account().get("failed_login_count"));
    }

    @Test
    void successResetsTheFailureCountSoOccasionalTyposNeverLock() {
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                assertEquals(401, login(EMAIL, WRONG).statusCode());
            }
            assertEquals(200, login(EMAIL, PASSPHRASE).statusCode());
        }
        assertNull(account().get("locked_until"));
    }

    @Test
    void parallelGuessesCannotExceedTheLockoutLimit() throws Exception {
        int guesses = 24;
        CyclicBarrier start = new CyclicBarrier(guesses);
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        try {
            for (var result : pool.invokeAll(IntStream.range(0, guesses).<Callable<Integer>>mapToObj(i -> () -> {
                start.await();
                return login(EMAIL, WRONG + i).statusCode();
            }).toList())) {
                assertEquals(401, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(5, account().get("failed_login_count"), "only five attempts may reach the password check");
        assertNotNull(account().get("locked_until"));
        assertEquals(401, login(EMAIL, PASSPHRASE).statusCode());
    }

    @Test
    void identityEndpointReturnsCurrentAccountAndRoles() {
        String token = accessToken();

        HttpResponse<String> response = api.get("/api/v1/auth/me", token);

        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> identity = HttpApi.parse(response);
        assertEquals(Set.of("accountId", "academyId", "roles"), identity.keySet());
        assertEquals(studentId.toString(), identity.get("accountId"));
        assertEquals(academyId.toString(), identity.get("academyId"));
        assertEquals(List.of("STUDENT"), identity.get("roles"));

        // Roles are read from the database, not from the token: a staff role added now needs a session that passed MFA.
        jdbc.update("INSERT INTO account_roles (account_id, role) VALUES (?, 'ANALYST')", studentId);
        assertEquals(401, api.get("/api/v1/auth/me", token).statusCode());
    }

    @Test
    void identityEndpointRequiresAValidBearerToken() {
        assertEquals(401, me(null));
        assertEquals(401, me("not-a-jwt"));
        assertEquals(401, me(accessToken() + "x"));
    }

    @Test
    void disablingAnAccountRejectsItsExistingTokens() {
        String token = accessToken();
        assertEquals(200, me(token));

        accounts.disable(studentId);

        assertEquals(401, me(token));
    }

    @Test
    void changingThePasswordRejectsEarlierTokens() {
        String token = accessToken();

        accounts.setPassword(studentId, "a different long passphrase");

        assertEquals(401, me(token));
        assertEquals(401, login(EMAIL, PASSPHRASE).statusCode());
        assertEquals(200, login(EMAIL, "a different long passphrase").statusCode());
    }

    @Test
    void tokenForADeletedAccountIsRejected() throws Exception {
        String token = accessToken();
        jdbc.update("DELETE FROM accounts WHERE id = ?", studentId);
        assertEquals(401, me(token));
    }

    @Test
    void correctlySignedTokensWithBadClaimsAreRejected() throws Exception {
        assertEquals(200, me(forged(claims -> { })), "the unaltered baseline token must be accepted");

        Date past = Date.from(Instant.now().minusSeconds(3600));
        Date future = Date.from(Instant.now().plusSeconds(3600));
        Map<String, Consumer<JWTClaimsSet.Builder>> alterations = new LinkedHashMap<>();
        alterations.put("expired", claims -> claims.expirationTime(past));
        alterations.put("not yet valid", claims -> claims.notBeforeTime(future));
        alterations.put("wrong issuer", claims -> claims.issuer("https://attacker.example.invalid"));
        alterations.put("wrong audience", claims -> claims.audience("another-api"));
        alterations.put("no audience", claims -> claims.audience((String) null));
        alterations.put("no token id", claims -> claims.jwtID(null));
        alterations.put("no subject", claims -> claims.subject(null));
        alterations.put("no expiry", claims -> claims.expirationTime(null));
        alterations.put("no issued-at", claims -> claims.issueTime(null));
        alterations.put("no not-before", claims -> claims.notBeforeTime(null));
        alterations.put("unknown subject", claims -> claims.subject(UUID.randomUUID().toString()));
        alterations.put("non-uuid subject", claims -> claims.subject("admin"));
        alterations.put("stale auth version", claims -> claims.claim("auth_version", 0));
        alterations.put("no auth version", claims -> claims.claim("auth_version", null));
        alterations.put("no session", claims -> claims.claim("sid", null));
        alterations.put("unknown session", claims -> claims.claim("sid", UUID.randomUUID().toString()));
        alterations.put("malformed session", claims -> claims.claim("sid", "1"));
        alterations.put("other academy", claims -> claims.claim("academy_id", UUID.randomUUID().toString()));
        for (var alteration : alterations.entrySet()) {
            assertEquals(401, me(forged(alteration.getValue())), alteration.getKey());
        }
    }

    @Test
    void tokensNotSignedByAKnownRs256KeyAreRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey foreign = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        RSAKey real = keys.signingJwk();

        assertEquals(401, me(forged(new RSASSASigner(foreign),
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(real.getKeyID()).build(), claims -> { })),
                "foreign key reusing the real key id");
        assertEquals(401, me(forged(new RSASSASigner(foreign),
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unknown-key").build(), claims -> { })), "unknown key id");
        assertEquals(401, me(forged(new RSASSASigner(real),
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims -> { })), "missing key id");
        assertEquals(401, me(forged(new RSASSASigner(real),
                new JWSHeader.Builder(JWSAlgorithm.RS512).keyID(real.getKeyID()).build(), claims -> { })), "other RSA algorithm");
        assertEquals(401, me(forged(new MACSigner(real.toRSAPublicKey().getEncoded()),
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(real.getKeyID()).build(), claims -> { })),
                "HS256 keyed with the public key (algorithm confusion)");

        Instant now = Instant.now();
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder().issuer(properties.issuer())
                .audience(properties.audience()).subject(studentId.toString()).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("academy_id", academyId.toString()).claim("auth_version", 1).build()).serialize();
        assertEquals(401, me(unsigned), "alg none");

        String[] parts = accessToken().split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String tampered = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.replace("\"auth_version\":1", "\"auth_version\":1 ").getBytes());
        assertEquals(401, me(parts[0] + "." + tampered + "." + parts[2]), "payload altered after signing");
    }

    @Test
    void staffAccountsNeverReceiveTokensFromThePasswordStepAlone() {
        for (Role role : List.of(Role.ACADEMY_OWNER, Role.ACADEMY_ADMIN, Role.INSTRUCTOR, Role.ATTENDANCE_OFFICER, Role.ANALYST)) {
            String email = role.name().toLowerCase().replace('_', '.') + "@example.invalid";
            accounts.setPassword(accounts.register(academyId, email, Set.of(role)).id(), PASSPHRASE);

            HttpResponse<String> response = login(email, PASSPHRASE);

            assertEquals(200, response.statusCode(), role.name());
            Map<String, Object> body = HttpApi.parse(response);
            assertEquals(Set.of("mfaRequired", "mfaToken", "mfaExpiresIn", "mfaEnrolled"), body.keySet());
            assertEquals(true, body.get("mfaRequired"));
            assertEquals(401, me((String) body.get("mfaToken")), "a challenge is not an access token");
            assertEquals(401, login(email, WRONG).statusCode(), "a wrong password must not reveal the MFA requirement");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_sessions", Integer.class));
    }

    @Test
    void activationRouteRedeemsAnInvitationOnceAndEnablesLogin() {
        String email = "invited@example.invalid";
        UUID invitedId = accounts.register(academyId, email, Set.of(Role.STUDENT)).id();
        String token = invitations.issue(invitedId, null).token();
        Map<String, String> weak = new LinkedHashMap<>();
        weak.put("token", token);
        weak.put("password", "too-short");
        Map<String, String> good = new LinkedHashMap<>(weak);
        good.put("password", PASSPHRASE);
        Map<String, String> unknown = new LinkedHashMap<>(good);
        unknown.put("token", "A".repeat(43));

        assertEquals(401, login(email, PASSPHRASE).statusCode());
        assertEquals(400, api.post("/api/v1/auth/activate", HttpApi.json(unknown)).statusCode());
        HttpResponse<String> rejected = api.post("/api/v1/auth/activate", HttpApi.json(weak));
        assertEquals(422, rejected.statusCode());
        assertFalse(rejected.body().contains("too-short"));

        assertEquals(204, api.post("/api/v1/auth/activate", HttpApi.json(good)).statusCode());

        assertEquals(200, login(email, PASSPHRASE).statusCode());
        HttpResponse<String> reused = api.post("/api/v1/auth/activate", HttpApi.json(good));
        assertEquals(400, reused.statusCode());
        assertEquals(api.post("/api/v1/auth/activate", HttpApi.json(unknown)).body(), reused.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwksEndpointPublishesOnlyPublicKeyMaterial() {
        HttpResponse<String> response = api.get("/.well-known/jwks.json", null);

        assertEquals(200, response.statusCode());
        List<Map<String, Object>> published = (List<Map<String, Object>>) HttpApi.parse(response).get("keys");
        assertEquals(1, published.size());
        assertEquals(keys.signingKeyId(), published.get(0).get("kid"));
        assertEquals(Set.of("kty", "kid", "use", "alg", "n", "e"), published.get(0).keySet());
    }

    @Test
    void everythingElseStaysDeniedEvenWithAValidToken() {
        String token = accessToken();
        for (String path : List.of("/api/v1/accounts", "/api/v1/courses", "/api/v1/auth/login", "/actuator/env", "/error")) {
            int status = api.get(path, token).statusCode();
            assertTrue(status == 401 || status == 403, path + " returned " + status);
        }
        assertEquals(401, api.post("/api/v1/auth/me", "{}").statusCode());
        assertEquals(401, api.post("/api/v1/auth/sessions", "{}").statusCode());
    }
}
