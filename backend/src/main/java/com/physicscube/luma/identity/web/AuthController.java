package com.physicscube.luma.identity.web;

import com.physicscube.luma.identity.AccessToken;
import com.physicscube.luma.identity.AuthService;
import com.physicscube.luma.identity.InvitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    record ActivateRequest(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 256) String password) {
    }

    record IdentityResponse(String accountId, String academyId, List<String> roles) {
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        AccessToken token = auth.login(request.email(), request.password());
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
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
