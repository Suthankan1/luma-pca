package com.physicscube.luma.academy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Owns a separate, optional Academy connection pool. Do not publish it as a DataSource bean:
 * doing so would prevent Spring from creating LUMA's primary datasource and could redirect
 * Flyway migrations into the Academy database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "luma.academy-payments", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AcademyPaymentsProperties.class)
class AcademyPaymentsConfiguration {
    @Bean(destroyMethod = "close")
    AcademyPaymentsCatalog academyPaymentsCatalog(AcademyPaymentsProperties properties) {
        if (isBlank(properties.jdbcUrl()) || isBlank(properties.username()) || isBlank(properties.password())) {
            throw new IllegalStateException("Academy payments connection requires JDBC URL, username and password");
        }
        HikariConfig pool = new HikariConfig();
        pool.setPoolName("academy-payments-readonly");
        pool.setJdbcUrl(properties.jdbcUrl());
        pool.setUsername(properties.username());
        pool.setPassword(properties.password());
        pool.setReadOnly(true);
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(5_000);
        pool.setValidationTimeout(2_000);
        pool.setInitializationFailTimeout(-1);
        HikariDataSource dataSource = new HikariDataSource(pool);
        return new AcademyPaymentsCatalog(new JdbcTemplate(dataSource), dataSource);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
