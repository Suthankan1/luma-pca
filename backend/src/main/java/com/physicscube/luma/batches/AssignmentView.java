package com.physicscube.luma.batches;

import java.time.Instant;
import java.util.UUID;

public record AssignmentView(UUID id, UUID batchId, UUID courseId, String batchCode, String batchName,
                             UUID accountId, String email, String role, Instant createdAt) {
}
