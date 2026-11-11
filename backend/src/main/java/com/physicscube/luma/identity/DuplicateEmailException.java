package com.physicscube.luma.identity;

/** Carries no email address so it is safe to log. */
public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("An account with this email already exists");
    }
}
