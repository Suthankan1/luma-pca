package com.physicscube.luma.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.Role;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** The per-request check, read in one query (RefreshSessions.requestAuth); the integration tests cover the SQL itself. */
class AccountJwtAuthenticationConverterTest {
    private RefreshSessions refreshSessions;
    private AccountJwtAuthenticationConverter converter;

    private final UUID accountId = UUID.randomUUID();
    private final UUID academyId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final long authVersion = 1L;

    @BeforeEach
    void setUp() {
        refreshSessions = mock(RefreshSessions.class);
        converter = new AccountJwtAuthenticationConverter(mock(AccountService.class), refreshSessions);
    }

    private Jwt jwt(long version, UUID academy) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600), Map.of("alg", "RS256"), Map.of(
                "sub", accountId.toString(),
                AccessTokenIssuer.SESSION_CLAIM, sessionId.toString(),
                AccessTokenIssuer.AUTH_VERSION_CLAIM, version,
                AccessTokenIssuer.ACADEMY_CLAIM, academy.toString()));
    }

    private void found(AccountStatus status, Set<Role> roles) {
        when(refreshSessions.requestAuth(sessionId, accountId))
                .thenReturn(new RefreshSessions.RequestAuth(academyId, status, (int) authVersion, roles));
    }

    @Test
    @DisplayName("A valid token for an active account gets the roles stored now")
    void convertsValidToken() {
        found(AccountStatus.ACTIVE, Set.of(Role.INSTRUCTOR));
        Authentication auth = converter.convert(jwt(authVersion, academyId));
        assertThat(auth.getName()).isEqualTo(accountId.toString());
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_INSTRUCTOR");
    }

    @Test
    @DisplayName("Disabled accounts, ended sessions, old auth versions and other academies are refused")
    void refusals() {
        found(AccountStatus.DISABLED, Set.of(Role.INSTRUCTOR));
        assertThatThrownBy(() -> converter.convert(jwt(authVersion, academyId))).isInstanceOf(InvalidBearerTokenException.class);

        when(refreshSessions.requestAuth(sessionId, accountId)).thenReturn(null); // revoked, expired, or account gone
        assertThatThrownBy(() -> converter.convert(jwt(authVersion, academyId))).isInstanceOf(InvalidBearerTokenException.class);

        found(AccountStatus.ACTIVE, Set.of(Role.INSTRUCTOR));
        assertThatThrownBy(() -> converter.convert(jwt(authVersion + 1, academyId))).isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(jwt(authVersion, UUID.randomUUID()))).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("Owners and administrators are accepted on any live password session")
    void elevatedNeedsOnlyALiveSession() {
        found(AccountStatus.ACTIVE, Set.of(Role.ACADEMY_OWNER, Role.ACADEMY_ADMIN));
        assertThat(converter.convert(jwt(authVersion, academyId)).getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ACADEMY_OWNER", "ROLE_ACADEMY_ADMIN");
    }
}
