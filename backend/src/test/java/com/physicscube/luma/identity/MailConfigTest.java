package com.physicscube.luma.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** SMTP login follows the configured username, so Gmail logs in and a local test server needs none. */
class MailConfigTest {
    private static String login(String username) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setUsername(username);
        return ((JavaMailSenderImpl) MailConfig.smtpLogin().postProcessAfterInitialization(sender, "mailSender"))
                .getJavaMailProperties().getProperty("mail.smtp.auth");
    }

    @Test
    void loginIsOnWithAUsernameAndOffWithout() {
        assertEquals("true", login("academy.sender@gmail.example"));
        assertEquals("false", login(null));
        assertEquals("false", login(" "));
    }

    @Test
    void anExplicitSettingIsKept() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.getJavaMailProperties().setProperty("mail.smtp.auth", "false");
        sender.setUsername("someone@example.test");
        MailConfig.smtpLogin().postProcessAfterInitialization(sender, "mailSender");
        assertEquals("false", sender.getJavaMailProperties().getProperty("mail.smtp.auth"));
    }
}
