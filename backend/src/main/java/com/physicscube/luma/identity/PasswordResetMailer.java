package com.physicscube.luma.identity;

import java.time.Instant;

/** Delivers a reset link. Implementations must not log the address or the link. */
public interface PasswordResetMailer {
    void send(String toAddress, String link, Instant expiresAt);
}
