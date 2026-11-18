package com.physicscube.luma.academy;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only adapter for Academy course memberships stored as payment rows.
 * It deliberately never reads or writes the Academy's attendance table.
 */
public final class AcademyPaymentsCatalog implements AutoCloseable {
    static final String CLASSES_SQL = """
            SELECT BTRIM(class_type) AS class_name,
                   COUNT(DISTINCT BTRIM(pcaid)) AS student_count
            FROM public.payments
            WHERE type = 'class'
              AND deleted_at IS NULL
              AND NULLIF(BTRIM(class_type), '') IS NOT NULL
              AND NULLIF(BTRIM(pcaid), '') IS NOT NULL
            GROUP BY BTRIM(class_type)
            ORDER BY BTRIM(class_type)
            """;

    static final String STUDENT_IDS_SQL = """
            SELECT DISTINCT BTRIM(pcaid) AS student_id
            FROM public.payments
            WHERE type = 'class'
              AND deleted_at IS NULL
              AND NULLIF(BTRIM(pcaid), '') IS NOT NULL
              AND BTRIM(class_type) = ?
            ORDER BY student_id
            """;

    private final JdbcTemplate jdbc;
    private final AutoCloseable ownedConnectionPool;

    AcademyPaymentsCatalog(JdbcTemplate jdbc, AutoCloseable ownedConnectionPool) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ownedConnectionPool = Objects.requireNonNull(ownedConnectionPool, "ownedConnectionPool");
    }

    /** Counts distinct student IDs, not payments. A payment is not proof of Zoom attendance. */
    public List<AcademyPaymentClass> listClasses() {
        return jdbc.query(CLASSES_SQL, (rs, rowNumber) ->
                new AcademyPaymentClass(rs.getString("class_name"), rs.getLong("student_count")));
    }

    /** IDs needed later to match a class's expected roster with Zoom evidence. */
    public List<String> studentIdsForClass(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className is required");
        }
        return jdbc.queryForList(STUDENT_IDS_SQL, String.class, className.strip());
    }

    @Override
    public void close() throws Exception {
        ownedConnectionPool.close();
    }
}
