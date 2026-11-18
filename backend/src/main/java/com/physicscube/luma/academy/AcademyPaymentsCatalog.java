package com.physicscube.luma.academy;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only adapter for Academy class memberships stored as payment rows. It reads only the columns it needs
 * ({@code pcaid}, {@code type}, {@code class_type}, {@code deleted_at}; and {@code pcaid}, {@code name},
 * {@code mail}, {@code deleted_at} of the student table), never payment amounts, and never the Academy's
 * attendance table. Table names come from configuration, are checked against a strict pattern and are the only
 * text placed into SQL; every value is a bound parameter.
 */
public final class AcademyPaymentsCatalog implements AutoCloseable {
    private static final Pattern TABLE = Pattern.compile("^[a-z_][a-z0-9_]{0,62}\\.[a-z_][a-z0-9_]{0,62}$");

    private final JdbcTemplate jdbc;
    private final AutoCloseable ownedConnectionPool;
    private final String classesSql;
    private final String studentIdsSql;
    private final String rosterSql;
    private final String classPaymentType;

    AcademyPaymentsCatalog(JdbcTemplate jdbc, AutoCloseable ownedConnectionPool, String paymentTable, String studentTable,
                           String classPaymentType) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ownedConnectionPool = Objects.requireNonNull(ownedConnectionPool, "ownedConnectionPool");
        this.classPaymentType = Objects.requireNonNull(classPaymentType, "classPaymentType");
        String payments = requireTable(paymentTable, "payment table");
        String classRows = """
                FROM %s
                WHERE lower(btrim(type::text)) = lower(?)
                  AND deleted_at IS NULL
                  AND NULLIF(btrim(class_type::text), '') IS NOT NULL
                  AND NULLIF(btrim(pcaid::text), '') IS NOT NULL
                """.formatted(payments);
        classesSql = """
                SELECT btrim(class_type::text) AS class_name, count(DISTINCT upper(btrim(pcaid::text))) AS student_count
                %s
                GROUP BY btrim(class_type::text)
                ORDER BY btrim(class_type::text)
                """.formatted(classRows);
        studentIdsSql = """
                SELECT DISTINCT upper(btrim(pcaid::text)) AS student_id
                %s
                  AND btrim(class_type::text) = ?
                ORDER BY student_id
                """.formatted(classRows);
        if (studentTable == null || studentTable.isBlank()) {
            rosterSql = """
                    SELECT student_id, NULL::text AS name, NULL::text AS email, 0 AS detail_rows
                    FROM (%s) ids
                    ORDER BY student_id
                    """.formatted(studentIdsSql.replace("ORDER BY student_id", ""));
        } else {
            String students = requireTable(studentTable, "student table");
            rosterSql = """
                    SELECT ids.student_id,
                           CASE WHEN d.n = 1 THEN d.name END AS name,
                           CASE WHEN d.n = 1 THEN d.email END AS email,
                           coalesce(d.n, 0) AS detail_rows
                    FROM (%s) ids
                    LEFT JOIN LATERAL (
                        SELECT count(*) AS n, min(btrim(s.name::text)) AS name, min(btrim(s.mail::text)) AS email
                        FROM %s s
                        WHERE upper(btrim(s.pcaid::text)) = ids.student_id AND s.deleted_at IS NULL
                    ) d ON TRUE
                    ORDER BY ids.student_id
                    """.formatted(studentIdsSql.replace("ORDER BY student_id", ""), students);
        }
    }

    static String requireTable(String name, String what) {
        if (name == null || !TABLE.matcher(name).matches()) {
            throw new IllegalStateException("The Academy " + what + " must be a lower-case schema.table name");
        }
        return name;
    }

    /** Counts distinct student IDs, not payments. A payment is not proof of Zoom attendance. */
    public List<AcademyPaymentClass> listClasses() {
        return jdbc.query(classesSql, (rs, rowNumber) ->
                new AcademyPaymentClass(rs.getString("class_name"), rs.getLong("student_count")), classPaymentType);
    }

    /** Distinct, upper-cased student IDs of one class. */
    public List<String> studentIdsForClass(String className) {
        return jdbc.queryForList(studentIdsSql, String.class, classPaymentType, requireClass(className));
    }

    /** The class's students with the minimum details needed to recognise them in LUMA and in Zoom. */
    public List<AcademyStudent> rosterForClass(String className) {
        return jdbc.query(rosterSql, (rs, rowNumber) -> new AcademyStudent(rs.getString("student_id"),
                        blankToNull(rs.getString("name")), blankToNull(rs.getString("email")), rs.getInt("detail_rows")),
                classPaymentType, requireClass(className));
    }

    private static String requireClass(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className is required");
        }
        return className.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public void close() throws Exception {
        ownedConnectionPool.close();
    }
}
