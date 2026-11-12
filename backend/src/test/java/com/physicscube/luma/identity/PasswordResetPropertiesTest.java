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

class PasswordResetPropertiesTest {
    private static PasswordResetProperties enabled(String from, String origin) {
        return new PasswordResetProperties(true, from, origin, Duration.ofMinutes(30), 3);
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
    }

    @Test
    void disabledNeedsNoSenderOrOriginButLimitsAreAlwaysChecked() {
        new PasswordResetProperties(false, null, null, Duration.ofMinutes(30), 3);
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetProperties(false, null, null, Duration.ofMinutes(1), 3));
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetProperties(false, null, null, Duration.ofMinutes(30), 0));
    }

    @Test
    void theEmailIsPlainTextWithTheLinkAndItsLifetime() {
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
        new SmtpPasswordResetMailer(sender, "no-reply@example.invalid")
                .send("person@example.invalid", "https://luma.example.invalid/reset-password#token=abc", Instant.now().plusSeconds(1800));
        SimpleMailMessage message = sent.get(0);
        assertEquals("no-reply@example.invalid", message.getFrom());
        assertEquals("person@example.invalid", message.getTo()[0]);
        assertEquals("Reset your LUMA password", message.getSubject());
        assertTrue(message.getText().contains("within 30 minutes:\nhttps://luma.example.invalid/reset-password#token=abc\n"), message.getText());
        assertTrue(message.getText().contains("still need their authenticator code"));
    }
}
