package com.physicscube.luma.academy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the Academy's existing database, never for LUMA's own Flyway database.
 *
 * @param paymentTable     schema-qualified table of payment rows (the Academy's is {@code public.payment})
 * @param studentTable     schema-qualified table of student details, read for {@code pcaid}, {@code name} and
 *                         {@code mail} only; blank when not authorised (students then appear by ID alone)
 * @param classPaymentType value of {@code type} that marks a class payment (compared case-insensitively)
 */
@ConfigurationProperties(prefix = "luma.academy-payments")
public record AcademyPaymentsProperties(boolean enabled, String jdbcUrl, String username, String password,
                                        String paymentTable, String studentTable, String classPaymentType) {
    public AcademyPaymentsProperties {
        paymentTable = paymentTable == null || paymentTable.isBlank() ? "public.payment" : paymentTable.strip();
        studentTable = studentTable == null ? "" : studentTable.strip();
        classPaymentType = classPaymentType == null || classPaymentType.isBlank() ? "class" : classPaymentType.strip();
    }
}
