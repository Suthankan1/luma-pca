package com.physicscube.luma.identity;

import java.time.Instant;
import java.util.UUID;

/** The only place the raw token exists. Deliver it to the invitee and do not log or store it. */
public record IssuedInvitation(UUID accountId, String token, Instant expiresAt) {
    @Override
    public String toString() {
        return "IssuedInvitation[accountId=" + accountId + ", token=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
