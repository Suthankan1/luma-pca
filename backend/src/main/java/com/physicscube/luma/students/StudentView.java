package com.physicscube.luma.students;

import com.physicscube.luma.identity.AccountSummary;
import java.time.Instant;
import java.util.UUID;

/** {@code accountStatus} tells staff whether the student has activated their sign-in yet. */
public record StudentView(UUID id, String studentNumber, String fullName, String email, UUID accountId,
                          String accountStatus, boolean active, long version, Instant createdAt, Instant updatedAt) {
    static StudentView of(Student student, AccountSummary account) {
        return new StudentView(student.getId(), student.getStudentNumber(), student.getFullName(),
                account == null ? null : account.email(), student.getAccountId(),
                account == null ? null : account.status().name(), student.isActive(), student.getVersion(),
                student.getCreatedAt(), student.getUpdatedAt());
    }
}
