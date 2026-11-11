package com.physicscube.luma.identity;

/** One outcome for unknown, expired, already-used and revoked refresh tokens. */
public class InvalidSessionException extends RuntimeException {
    public InvalidSessionException() {
        super("Session is invalid or has expired");
    }
}
