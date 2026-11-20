package com.physicscube.luma.identity.web;

import com.physicscube.luma.identity.AccountInvitationService;
import com.physicscube.luma.identity.IssuedInvitation;
import com.physicscube.luma.shared.security.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountInvitationController {
    private final AccountInvitationService invitations;

    AccountInvitationController(AccountInvitationService invitations) {
        this.invitations = invitations;
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

    /**
     * For a pending account of the academy; replaces any earlier invitation. {@code delivery} LINK (the default, also
     * with no body) returns the link for the staff member to send. EMAIL emails it to the account's address and does
     * not return it, so the token reaches only the invitee; 503 when email is not set up.
     */
    @PostMapping("/api/v1/accounts/{accountId}/invitations")
    ResponseEntity<InvitationResponse> invite(JwtAuthenticationToken authentication, @PathVariable UUID accountId,
                                              @Valid @RequestBody(required = false) InviteRequest request) {
        boolean email = request != null && "EMAIL".equals(request.delivery());
        IssuedInvitation issued = invitations.invite(CurrentAccount.from(authentication), accountId, email);
        InvitationResponse body = email
                ? new InvitationResponse(issued.accountId(), null, null, issued.expiresAt(), "EMAIL")
                : new InvitationResponse(issued.accountId(), issued.token(), "/activate#token=" + issued.token(),
                        issued.expiresAt(), "LINK");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }
}
