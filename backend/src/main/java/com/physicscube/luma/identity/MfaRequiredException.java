package com.physicscube.luma.identity;

/** Raised only after the password was verified, so it reveals nothing to a guesser. */
public class MfaRequiredException extends RuntimeException {
    public MfaRequiredException() {
        super("Multi-factor authentication is required for this account and is not available yet");
    }
}
