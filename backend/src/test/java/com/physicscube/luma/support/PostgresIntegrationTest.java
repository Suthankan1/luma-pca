package com.physicscube.luma.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole application against a disposable PostgreSQL 17 (the production
 * target version). Flyway applies every migration and Hibernate validates the
 * entity mappings against the result. One container is shared by all subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"luma.auth.ephemeral-signing-key=true",
        // Tests drive the receipt processor directly; no background polling.
        "luma.zoom.processing.enabled=false",
        // Tests drive Zoom report fetching directly.
        "luma.zoom.reports.enabled=false",
        // Tests run retention directly.
        "luma.retention.enabled=false",
        // Tests drive the invitation email sender directly.
        "luma.mail.bulk.enabled=false",
        // Every test signs in from this machine; AuthRateLimitIntegrationTest turns limits on with small values.
        "luma.rate-limit.enabled=false",
        // Small pools: many test contexts share one database.
        "spring.datasource.hikari.maximum-pool-size=5"})
public abstract class PostgresIntegrationTest {
    @ServiceConnection
    // Several cached application contexts (different test properties) each hold a connection pool: allow plenty.
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    @Autowired
    private JdbcTemplate resetJdbc;

    /** Empties every table that hangs off an academy, whatever later migrations add. */
    protected void resetDatabase() {
        resetJdbc.execute("TRUNCATE academies CASCADE");
    }
}
