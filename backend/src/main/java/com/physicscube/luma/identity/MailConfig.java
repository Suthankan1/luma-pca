package com.physicscube.luma.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSender;

@Configuration
@EnableConfigurationProperties({MailProperties.class, PasswordResetProperties.class})
class MailConfig {
    /**
     * SMTP login whenever a username is configured (Gmail and most providers require it), and none otherwise (a local test
     * server): JavaMail refuses to connect when login is on but no password is given.
     */
    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor smtpLogin() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof org.springframework.mail.javamail.JavaMailSenderImpl mail
                        && !mail.getJavaMailProperties().containsKey("mail.smtp.auth")) {
                    boolean login = mail.getUsername() != null && !mail.getUsername().isBlank();
                    mail.getJavaMailProperties().setProperty("mail.smtp.auth", String.valueOf(login));
                }
                return bean;
            }
        };
    }

    /** Fails at startup when email is enabled without an SMTP server, rather than on the first message. */
    @Bean
    AccountMailer accountMailer(MailProperties properties, ObjectProvider<MailSender> sender) {
        MailSender available = sender.getIfAvailable();
        if (properties.enabled() && available == null) {
            throw new IllegalStateException("LUMA_MAIL_ENABLED=true needs an SMTP server: set SPRING_MAIL_HOST");
        }
        if (available == null) {
            return new AccountMailer() {
                @Override
                public void invitation(String to, String link, java.time.Instant expiresAt) {
                    throw new IllegalStateException("Email is not configured");
                }

                @Override
                public void passwordReset(String to, String link, java.time.Instant expiresAt) {
                    throw new IllegalStateException("Email is not configured");
                }

                @Override
                public void notice(String to, String subject, String text) {
                    throw new IllegalStateException("Email is not configured");
                }
            };
        }
        return new SmtpAccountMailer(available, properties.from());
    }
}
