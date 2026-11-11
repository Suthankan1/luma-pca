package com.physicscube.luma.zoom;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns one stored receipt into business records. Runs inside the transaction that
 * claimed the receipt, so it must be idempotent, must not call external services,
 * and must write through {@code JdbcTemplate}: a failed attempt is undone with a JDBC
 * savepoint, which would not reset JPA entities held in memory.
 */
public interface ReceiptHandler {
    record Receipt(UUID id, UUID academyId, String eventType, long eventTs, Map<String, Object> body) {
    }

    Set<String> eventTypes();

    void handle(Receipt receipt);
}
