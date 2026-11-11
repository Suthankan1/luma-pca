package com.physicscube.luma.zoom;

import java.time.Instant;
import java.util.UUID;

/** Operational view of a receipt. Deliberately excludes the payload, which holds names and emails. */
public record ZoomReceiptView(UUID id, String eventType, long eventTs, String status, int attempts, String lastError,
                              Instant receivedAt, Instant nextAttemptAt, Instant processedAt) {
}
