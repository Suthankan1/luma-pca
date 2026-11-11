package com.physicscube.luma.identity.domain;

/** The message describes the rule that failed and never contains the rejected password. */
public class PasswordPolicyException extends RuntimeException {
    public PasswordPolicyException(String message) {
        super(message);
    }
}
