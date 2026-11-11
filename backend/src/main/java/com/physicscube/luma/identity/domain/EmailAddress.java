package com.physicscube.luma.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** Normalises the login identifier so uniqueness does not depend on letter case or padding. */
public final class EmailAddress {
    public static final int MAX_LENGTH = 254;
    private static final Pattern SHAPE = Pattern.compile("^[^\\s@]{1,64}@[^\\s@.]+(\\.[^\\s@.]+)+$");

    private EmailAddress() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Email is required");
        }
        String email = raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > MAX_LENGTH || !SHAPE.matcher(email).matches()) {
            throw new IllegalArgumentException("Email is not a valid address");
        }
        return email;
    }
}
