package com.physicscube.luma.identity;

/**
 * One outcome for an unknown, expired or used challenge, a wrong or replayed code,
 * and an account that is locked or no longer eligible.
 */
public class InvalidMfaException extends RuntimeException {
    public InvalidMfaException() {
        super("Verification failed");
    }
}
