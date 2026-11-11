package com.physicscube.luma.shared.api;

import java.util.Locale;

/** Short human-assigned identifiers such as course and batch codes. */
public final class Codes {
    public static final String PATTERN = "^\\s*[A-Za-z0-9][A-Za-z0-9._-]{0,59}\\s*$";

    private Codes() {
    }

    public static String normalize(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }
}
