package com.physicscube.luma.identity.web;

import com.physicscube.luma.identity.IssuedInvitation;
import com.physicscube.luma.identity.StaffAccountService;
import com.physicscube.luma.identity.StaffAccountView;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StaffAccountController {
    private final StaffAccountService staff;

    StaffAccountController(StaffAccountService staff) {
        this.staff = staff;
    }

    record CreateStaffRequest(@NotBlank @Size(max = 254) String email, @NotNull @Size(max = 5) List<String> roles) {
    }

    record ReplaceRolesRequest(@NotNull @Size(max = 5) List<String> roles, @NotNull @PositiveOrZero Long version) {
    }

    /** The activation token is shown once; deliver it to the person and do not store it. */
    record InvitationResponse(UUID accountId, String activationToken, Instant expiresAt) {
    }

    @GetMapping("/api/v1/staff-accounts")
    List<StaffAccountView> list(JwtAuthenticationToken authentication) {
        return staff.list(CurrentAccount.from(authentication));
    }

    @GetMapping("/api/v1/staff-accounts/{accountId}")
    StaffAccountView get(JwtAuthenticationToken authentication, @PathVariable UUID accountId) {
        return staff.get(CurrentAccount.from(authentication), accountId);
    }

    @PostMapping("/api/v1/staff-accounts")
    ResponseEntity<StaffAccountView> create(JwtAuthenticationToken authentication, @Valid @RequestBody CreateStaffRequest request) {
        StaffAccountView created = staff.create(CurrentAccount.from(authentication), request.email(), request.roles());
        return ResponseEntity.created(URI.create("/api/v1/staff-accounts/" + created.id())).body(created);
    }

    @PutMapping("/api/v1/staff-accounts/{accountId}/roles")
    StaffAccountView replaceRoles(JwtAuthenticationToken authentication, @PathVariable UUID accountId,
                                  @Valid @RequestBody ReplaceRolesRequest request) {
        return staff.replaceRoles(CurrentAccount.from(authentication), accountId, request.roles(), request.version());
    }

    @PostMapping("/api/v1/staff-accounts/{accountId}/disable")
    StaffAccountView disable(JwtAuthenticationToken authentication, @PathVariable UUID accountId) {
        return staff.disable(CurrentAccount.from(authentication), accountId);
    }

    /** For any pending account of the academy, staff or student. Replaces any earlier invitation. */
    @PostMapping("/api/v1/accounts/{accountId}/invitations")
    ResponseEntity<InvitationResponse> invite(JwtAuthenticationToken authentication, @PathVariable UUID accountId) {
        IssuedInvitation issued = staff.invite(CurrentAccount.from(authentication), accountId);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new InvitationResponse(issued.accountId(), issued.token(), issued.expiresAt()));
    }
}
