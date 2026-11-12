package com.physicscube.luma.identity.web;

import com.physicscube.luma.identity.IssuedInvitation;
import com.physicscube.luma.identity.StaffAccountService;
import com.physicscube.luma.identity.StaffAccountView;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    record InviteRequest(@Pattern(regexp = "LINK|EMAIL") String delivery) {
    }

    /**
     * The activation token is shown once; deliver it to the person and do not store it.
     * {@code activationPath} is appended to the web app's origin to form the link; the
     * token sits in the fragment, which browsers never send to a server. Both are null
     * when the invitation was emailed.
     */
    record InvitationResponse(UUID accountId, String activationToken, String activationPath, Instant expiresAt, String delivery) {
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

    record EnableRequest(Boolean requireNewPassword) {
    }

    /** Re-enables a disabled staff account; with requireNewPassword it returns to pending activation. */
    @PostMapping("/api/v1/staff-accounts/{accountId}/enable")
    StaffAccountView enable(JwtAuthenticationToken authentication, @PathVariable UUID accountId,
                            @RequestBody(required = false) EnableRequest request) {
        return staff.enable(CurrentAccount.from(authentication), accountId,
                request != null && Boolean.TRUE.equals(request.requireNewPassword()));
    }

    @PostMapping("/api/v1/staff-accounts/{accountId}/disable")
    StaffAccountView disable(JwtAuthenticationToken authentication, @PathVariable UUID accountId) {
        return staff.disable(CurrentAccount.from(authentication), accountId);
    }

    /** For any pending account of the academy, staff or student. Replaces any earlier invitation. */
    /**
     * {@code delivery} LINK (the default, also with no body) returns the link for the
     * administrator to send. EMAIL emails it to the account's address and does not return
     * it, so the token reaches only the invitee; 503 when email is not set up.
     */
    @PostMapping("/api/v1/accounts/{accountId}/invitations")
    ResponseEntity<InvitationResponse> invite(JwtAuthenticationToken authentication, @PathVariable UUID accountId,
                                              @Valid @RequestBody(required = false) InviteRequest request) {
        boolean email = request != null && "EMAIL".equals(request.delivery());
        IssuedInvitation issued = staff.invite(CurrentAccount.from(authentication), accountId, email);
        InvitationResponse body = email
                ? new InvitationResponse(issued.accountId(), null, null, issued.expiresAt(), "EMAIL")
                : new InvitationResponse(issued.accountId(), issued.token(), "/activate#token=" + issued.token(),
                        issued.expiresAt(), "LINK");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }
}
