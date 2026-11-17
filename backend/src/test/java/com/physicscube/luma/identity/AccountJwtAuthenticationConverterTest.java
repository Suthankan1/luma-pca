package com.physicscube.luma.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.Role;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class AccountJwtAuthenticationConverterTest {
    private AccountService accountService;
    private RefreshSessions refreshSessions;
    private AccountJwtAuthenticationConverter converter;

    private final UUID accountId = UUID.randomUUID();
    private final UUID academyId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final long authVersion = 1L;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        refreshSessions = mock(RefreshSessions.class);
        converter = new AccountJwtAuthenticationConverter(accountService, refreshSessions);
    }

    private Jwt mockJwt() {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", accountId.toString(),
                        AccessTokenIssuer.SESSION_CLAIM, sessionId.toString(),
                        AccessTokenIssuer.AUTH_VERSION_CLAIM, authVersion,
                        AccessTokenIssuer.ACADEMY_CLAIM, academyId.toString()
                )
        );
    }

    @Test
    @DisplayName("Caches converted authentication and evicts immediately on demand")
    void cachesAndEvictsImmediately() {
        Jwt jwt = mockJwt();
        AccountSummary activeAccount = new AccountSummary(
                accountId, academyId, "user@test.com", AccountStatus.ACTIVE,
                Set.of(Role.INSTRUCTOR), (int) authVersion
        );

        when(accountService.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(refreshSessions.mfaStateIfActive(sessionId, accountId, (int) authVersion)).thenReturn(true);

        // First call populates cache
        Authentication auth1 = converter.convert(jwt);
        assertThat(auth1).isNotNull();
        assertThat(auth1.getName()).isEqualTo(accountId.toString());
        verify(accountService, times(1)).findById(accountId);

        // Second call hits cache (no new DB query)
        Authentication auth2 = converter.convert(jwt);
        assertThat(auth2).isNotNull();
        verify(accountService, times(1)).findById(accountId);

        // Evict account from cache
        converter.evict(accountId);

        // If account is disabled in DB, next call immediately rejects the token
        AccountSummary disabledAccount = new AccountSummary(
                accountId, academyId, "user@test.com", AccountStatus.DISABLED,
                Set.of(Role.INSTRUCTOR), (int) authVersion
        );
        when(accountService.findById(accountId)).thenReturn(Optional.of(disabledAccount));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
        verify(accountService, times(2)).findById(accountId);
    }
}
