package com.physicscube.luma.identity;

import java.time.Duration;
import java.time.Instant;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/** Plain-text email through the configured SMTP server (any provider that offers SMTP). */
class SmtpPasswordResetMailer implements PasswordResetMailer {
    private final MailSender sender;
    private final String from;

    SmtpPasswordResetMailer(MailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String toAddress, String link, Instant expiresAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toAddress);
        message.setSubject("Reset your LUMA password");
        message.setText(body(link, Duration.between(Instant.now(), expiresAt)));
        sender.send(message);
    }

    static String body(String link, Duration validFor) {
        long minutes = Math.max(1, (validFor.toSeconds() + 59) / 60);
        return """
                Someone asked to reset the password of your Physics Cube Academy LUMA account.

                To choose a new password, open this link within %d minutes:
                %s

                The link works once. Afterwards you are signed out on every device. Owners and
                administrators still need their authenticator code to sign in.

                If you did not ask for this, ignore this email; your password has not changed.
                """.formatted(minutes, link);
    }
}
