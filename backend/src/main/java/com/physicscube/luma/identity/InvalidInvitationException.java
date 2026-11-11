package com.physicscube.luma.identity;

/**
 * One message for unknown, expired, used and revoked tokens and for accounts that
 * can no longer be activated, so callers cannot probe which case applies.
 */
public class InvalidInvitationException extends RuntimeException {
    public InvalidInvitationException() {
        super("Invitation is invalid or has expired");
    }
}
