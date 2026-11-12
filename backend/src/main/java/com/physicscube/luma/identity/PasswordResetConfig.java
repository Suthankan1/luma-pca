package com.physicscube.luma.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSender;

@Configuration
@EnableConfigurationProperties(PasswordResetProperties.class)
class PasswordResetConfig {
    /** Fails at startup when reset is enabled without an SMTP server, rather than on the first request. */
    @Bean
    PasswordResetMailer passwordResetMailer(PasswordResetProperties properties, ObjectProvider<MailSender> sender) {
        MailSender available = sender.getIfAvailable();
        if (properties.enabled() && available == null) {
            throw new IllegalStateException("LUMA_PASSWORD_RESET_ENABLED=true needs an SMTP server: set SPRING_MAIL_HOST");
        }
        if (available == null) {
            return (to, link, expiresAt) -> {
                throw new IllegalStateException("Password reset email is not configured");
            };
        }
        return new SmtpPasswordResetMailer(available, properties.from());
    }
}
