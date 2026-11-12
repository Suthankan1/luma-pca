package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

class MailPropertiesTest {
    private static MailProperties enabled(String from, String origin) {
        return new MailProperties(true, from, origin);
    }

    @Test
    void theWebOriginMustBeHttpsExceptOnThisMachine() {
        assertEquals("https://luma.example.invalid", enabled("a@b.c", "https://luma.example.invalid/").webOrigin());
        assertEquals("https://luma.example.invalid:8443", enabled("a@b.c", " https://luma.example.invalid:8443 ").webOrigin());
        assertEquals("http://127.0.0.1:3200", enabled("a@b.c", "http://127.0.0.1:3200").webOrigin());
        assertEquals("http://localhost:3000", enabled("a@b.c", "http://localhost:3000").webOrigin());
        for (String bad : List.of("http://luma.example.invalid", "https://luma.example.invalid/app", "https://x.invalid?a=1",
                "https://user@x.invalid", "javascript:alert(1)", "", "not a url")) {
            assertThrows(IllegalArgumentException.class, () -> enabled("a@b.c", bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> enabled(null, "https://x.invalid"));
        assertThrows(IllegalArgumentException.class, () -> enabled("Name <a@b.c>", "https://x.invalid"));
        new MailProperties(false, null, null);
    }

    @Test
    void resetLimitsAreChecked() {
        new PasswordResetProperties(Duration.ofMinutes(30), 3);
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetProperties(Duration.ofMinutes(1), 3));
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetProperties(Duration.ofMinutes(30), 0));
    }

    @Test
    void emailsArePlainTextWithTheLinkAndItsLifetime() {
        List<SimpleMailMessage> sent = new ArrayList<>();
        MailSender sender = new MailSender() {
            @Override
            public void send(SimpleMailMessage message) {
                sent.add(message);
            }

            @Override
            public void send(SimpleMailMessage... messages) {
                sent.addAll(List.of(messages));
            }
        };
        SmtpAccountMailer mailer = new SmtpAccountMailer(sender, "no-reply@example.invalid");
        mailer.passwordReset("person@example.invalid", "https://luma.example.invalid/reset-password#token=abc", Instant.now().plusSeconds(1800));
        mailer.invitation("person@example.invalid", "https://luma.example.invalid/activate#token=def", Instant.now().plusSeconds(72 * 3600));

        SimpleMailMessage reset = sent.get(0);
        assertEquals("no-reply@example.invalid", reset.getFrom());
        assertEquals("person@example.invalid", reset.getTo()[0]);
        assertEquals("Reset your LUMA password", reset.getSubject());
        assertTrue(reset.getText().contains("within 30 minutes:\nhttps://luma.example.invalid/reset-password#token=abc\n"), reset.getText());
        assertTrue(reset.getText().contains("still need their authenticator code"));

        SimpleMailMessage invitation = sent.get(1);
        assertEquals("Your Physics Cube Academy LUMA account", invitation.getSubject());
        assertTrue(invitation.getText().contains("within 72 hours and choose a password:\nhttps://luma.example.invalid/activate#token=def\n"),
                invitation.getText());
    }
}
