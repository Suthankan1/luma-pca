package com.physicscube.luma.identity;

import java.time.Duration;
import java.time.Instant;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/** Plain-text email through the configured SMTP server (any provider that offers SMTP). */
class SmtpAccountMailer implements AccountMailer {
    private final MailSender sender;
    private final String from;

    SmtpAccountMailer(MailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void invitation(String toAddress, String link, Instant expiresAt) {
        send(toAddress, "Your Physics Cube Academy LUMA account", invitationBody(link, Duration.between(Instant.now(), expiresAt)));
    }

    @Override
    public void passwordReset(String toAddress, String link, Instant expiresAt) {
        send(toAddress, "Reset your LUMA password", resetBody(link, Duration.between(Instant.now(), expiresAt)));
    }

    @Override
    public void notice(String toAddress, String subject, String text) {
        send(toAddress, subject, text + "\n\nThis is an automatic message from Physics Cube Academy LUMA.\n");
    }

    private void send(String toAddress, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toAddress);
        message.setSubject(subject);
        message.setText(text);
        sender.send(message);
    }

    static String invitationBody(String link, Duration validFor) {
        long hours = Math.max(1, (validFor.toMinutes() + 59) / 60);
        return """
                Physics Cube Academy has created a LUMA account for you.

                To activate it, open this link within %d hours and choose a password:
                %s

                The link works once. If it has expired, ask the academy for a new invitation.

                If you were not expecting this email, you can ignore it.
                """.formatted(hours, link);
    }

    static String resetBody(String link, Duration validFor) {
        long minutes = Math.max(1, (validFor.toSeconds() + 59) / 60);
        return """
                Someone asked to reset the password of your Physics Cube Academy LUMA account.

                To choose a new password, open this link within %d minutes:
                %s

                The link works once. Afterwards you are signed out on every device.

                If you did not ask for this, ignore this email; your password has not changed.
                """.formatted(minutes, link);
    }
}
