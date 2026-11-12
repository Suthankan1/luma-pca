package com.physicscube.luma.identity;

public class PasswordResetUnavailableException extends RuntimeException {
    public PasswordResetUnavailableException() {
        super("Password reset by email is not available. Ask an academy administrator for help.");
    }
}
