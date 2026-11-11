package com.physicscube.luma.identity.web;

import com.physicscube.luma.identity.SessionTokens;
import com.physicscube.luma.identity.AuthService;
import com.physicscube.luma.identity.InvitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthService auth;
    private final InvitationService invitations;

    AuthController(AuthService auth, InvitationService invitations) {
        this.auth = auth;
        this.invitations = invitations;
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 256) String password) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {
        static TokenResponse of(SessionTokens tokens) {
            return new TokenResponse(tokens.accessToken(), "Bearer", tokens.expiresInSeconds(),
                    tokens.refreshToken(), tokens.refreshExpiresInSeconds());
        }
    }

    record MfaRequiredResponse(boolean mfaRequired, String mfaToken, long mfaExpiresIn, boolean mfaEnrolled) {
    }

    record MfaTokenRequest(@NotBlank @Size(max = 128) String mfaToken) {
    }

    record MfaVerifyRequest(@NotBlank @Size(max = 128) String mfaToken, @NotBlank @Size(max = 32) String code) {
    }

    record MfaEnrollmentResponse(String secret, String otpauthUri) {
    }

    record MfaVerifiedResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
                               long refreshExpiresIn, List<String> recoveryCodes) {
    }

    record MfaResetRequest(@NotBlank @Size(max = 256) String password) {
    }

    record RefreshTokenRequest(@NotBlank @Size(max = 128) String refreshToken) {
    }

    record ActivateRequest(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 256) String password) {
    }

    record IdentityResponse(String accountId, String academyId, List<String> roles) {
    }

    /** Returns tokens, or {@code mfaRequired} with a challenge when a second factor is needed. */
    @PostMapping("/login")
    Object login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginOutcome outcome = auth.login(request.email(), request.password());
        if (outcome.challenge() != null) {
            return new MfaRequiredResponse(true, outcome.challenge().mfaToken(), outcome.challenge().expiresInSeconds(),
                    outcome.challenge().enrolled());
        }
        return TokenResponse.of(outcome.tokens());
    }

    @PostMapping("/mfa/enroll")
    MfaEnrollmentResponse enrollMfa(@Valid @RequestBody MfaTokenRequest request) {
        AuthService.MfaEnrollment enrollment = auth.beginMfaEnrollment(request.mfaToken());
        return new MfaEnrollmentResponse(enrollment.secret(), enrollment.otpauthUri());
    }

    @PostMapping("/mfa/verify")
    MfaVerifiedResponse verifyMfa(@Valid @RequestBody MfaVerifyRequest request) {
        AuthService.MfaOutcome outcome = auth.completeMfa(request.mfaToken(), request.code());
        SessionTokens tokens = outcome.tokens();
        return new MfaVerifiedResponse(tokens.accessToken(), "Bearer", tokens.expiresInSeconds(), tokens.refreshToken(),
                tokens.refreshExpiresInSeconds(), outcome.recoveryCodes());
    }

    /** Removes the caller's authenticator; requires their password again and signs them out everywhere. */
    @PostMapping("/mfa/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetMfa(JwtAuthenticationToken authentication, @Valid @RequestBody MfaResetRequest request) {
        auth.resetMfa(UUID.fromString(authentication.getName()),
                UUID.fromString(authentication.getToken().getClaimAsString("sid")), request.password());
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return TokenResponse.of(auth.refresh(request.refreshToken()));
    }

    /** Ends the session of the presented refresh token. Always 204, so it reveals nothing about the token. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        auth.logout(request.refreshToken());
    }

    /** Ends every session of the signed-in account, on all devices. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutAll(JwtAuthenticationToken authentication) {
        auth.logoutAll(UUID.fromString(authentication.getName()));
    }

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activate(@Valid @RequestBody ActivateRequest request) {
        invitations.accept(request.token(), request.password());
    }

    /** Roles are the ones stored now, resolved while authenticating this request. */
    @GetMapping("/me")
    IdentityResponse me(JwtAuthenticationToken authentication) {
        return new IdentityResponse(authentication.getName(),
                authentication.getToken().getClaimAsString("academy_id"),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .filter(authority -> authority.startsWith("ROLE_"))
                        .map(authority -> authority.substring(5)).sorted().toList());
    }
}
