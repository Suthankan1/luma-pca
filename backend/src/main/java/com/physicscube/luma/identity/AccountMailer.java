package com.physicscube.luma.identity;

import java.time.Instant;

/** Emails links to account holders. Implementations must not log addresses or links. */
public interface AccountMailer {
    void invitation(String toAddress, String link, Instant expiresAt);

    void passwordReset(String toAddress, String link, Instant expiresAt);
}
