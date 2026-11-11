package com.physicscube.luma.identity.domain;

import java.util.Locale;

/**
 * Length-based password rules (no forced character classes). A breached-password
 * check is not included yet.
 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    private static final int MIN_LOCAL_PART_TO_COMPARE = 4;

    private PasswordPolicy() {
    }

    public static void validate(String password, String normalizedEmail) {
        if (password == null || password.isBlank()) {
            throw new PasswordPolicyException("Password is required");
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            throw new PasswordPolicyException("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (length > MAX_LENGTH) {
            throw new PasswordPolicyException("Password must be at most " + MAX_LENGTH + " characters");
        }
        if (password.codePoints().distinct().count() == 1) {
            throw new PasswordPolicyException("Password must not repeat a single character");
        }
        String localPart = normalizedEmail.substring(0, normalizedEmail.indexOf('@'));
        if (localPart.length() >= MIN_LOCAL_PART_TO_COMPARE
                && password.toLowerCase(Locale.ROOT).contains(localPart)) {
            throw new PasswordPolicyException("Password must not contain the account email name");
        }
    }
}
