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
        "luma.zoom.processing.enabled=false"})
public abstract class PostgresIntegrationTest {
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

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
