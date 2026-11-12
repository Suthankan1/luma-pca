package com.physicscube.luma.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param mfaEnrolled whether the account has a confirmed authenticator */
/** @param passwordSet whether the person has ever set a password (so re-enabling can restore it); never the password itself */
public record StaffAccountView(UUID id, String email, List<String> roles, String status, boolean mfaEnrolled,
                               long version, Instant createdAt, boolean passwordSet) {
}
