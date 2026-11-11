package com.physicscube.luma.enrollments;

import java.time.LocalDate;
import java.util.UUID;

/** @param status ENROLLED, or WITHDRAWN once a withdrawal date is set */
public record EnrollmentView(UUID id, UUID batchId, UUID courseId, String batchCode, String batchName,
                             UUID studentId, String studentNumber, String studentName,
                             LocalDate enrolledOn, LocalDate withdrawnOn, String status, long version) {
}
