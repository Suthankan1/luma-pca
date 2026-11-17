package com.physicscube.luma.identity;

import java.time.Instant;

/** Emails links to account holders. Implementations must not log addresses or links. */
public interface AccountMailer {
    void invitation(String toAddress, String link, Instant expiresAt);

    void passwordReset(String toAddress, String link, Instant expiresAt);

    /** A short plain-text notice (an appeal decided, a record corrected). Never contains secrets or links that sign in. */
    default void notice(String toAddress, String subject, String text) {
        throw new UnsupportedOperationException("This mailer does not send notices");
    }
}
