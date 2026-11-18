package com.physicscube.luma.academy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials for the Academy's existing database, never for LUMA's own Flyway database. */
@ConfigurationProperties(prefix = "luma.academy-payments")
public record AcademyPaymentsProperties(boolean enabled, String jdbcUrl, String username, String password) {}
