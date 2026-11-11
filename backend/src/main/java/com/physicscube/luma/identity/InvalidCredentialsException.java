package com.physicscube.luma.identity;

/** One outcome for unknown email, wrong password, inactive and locked accounts. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
