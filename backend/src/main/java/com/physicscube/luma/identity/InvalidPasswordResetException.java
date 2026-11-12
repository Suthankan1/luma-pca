package com.physicscube.luma.identity;

/** One message for unknown, expired, used and revoked links and for accounts that cannot be reset. */
public class InvalidPasswordResetException extends RuntimeException {
    public InvalidPasswordResetException() {
        super("This reset link is invalid, already used or expired. Ask for a new one.");
    }
}
