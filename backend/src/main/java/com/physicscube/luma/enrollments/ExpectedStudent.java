package com.physicscube.luma.enrollments;

import java.util.List;
import java.util.UUID;

/** A student expected at a class, listed once however many of its batches they belong to. */
public record ExpectedStudent(UUID studentId, String studentNumber, String fullName, List<UUID> batchIds) {
}
