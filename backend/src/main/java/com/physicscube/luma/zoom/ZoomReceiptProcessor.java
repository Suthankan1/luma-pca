package com.physicscube.luma.zoom;

import com.physicscube.luma.shared.time.DbTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes due receipts, at least once and safely with several workers.
 *
 * <p>Each receipt is claimed with {@code FOR UPDATE SKIP LOCKED} and handled and
 * marked in that same transaction. Two workers never hold the same receipt; a worker
 * that dies mid-way releases its lock and leaves the receipt due again. A handler
 * failure rolls back the handler's writes, then a separate transaction records the
 * attempt and schedules a retry, or dead-letters the receipt when the failure is
 * permanent or attempts are exhausted. Failure details are stored and logged without
 * payload content.
 */
@Service
@EnableConfigurationProperties(ZoomProcessingProperties.class)
public class ZoomReceiptProcessor {
    private static final Logger log = LoggerFactory.getLogger(ZoomReceiptProcessor.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ZoomProcessingProperties properties;
    private final Map<String, ReceiptHandler> handlers = new HashMap<>();

    ZoomReceiptProcessor(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ZoomProcessingProperties properties,
                         List<ReceiptHandler> handlerBeans) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        for (ReceiptHandler handler : handlerBeans) {
            for (String type : handler.eventTypes()) {
                if (handlers.put(type, handler) != null) {
                    throw new IllegalStateException("Two receipt handlers for " + type);
                }
            }
        }
    }

    /** Processes up to one batch of due receipts; returns how many were attempted. */
    public int runOnce() {
        int attempted = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            Outcome outcome = processNext();
            if (outcome == Outcome.NONE_DUE) {
                break;
            }
            attempted++;
        }
        return attempted;
    }

    enum Outcome { NONE_DUE, PROCESSED, RETRY, DEAD }

    Outcome processNext() {
        Failure[] failure = new Failure[1];
        Outcome outcome = transactions.execute(status -> {
            List<Map<String, Object>> due = jdbc.queryForList("""
                    SELECT id, academy_id, event_type, event_ts, attempts, payload::text AS body
                    FROM zoom_webhook_receipts
                    WHERE processing_status = 'RECEIVED' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at, received_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """, Timestamp.from(DbTime.now()));
            if (due.isEmpty()) {
                return Outcome.NONE_DUE;
            }
            Map<String, Object> row = due.get(0);
            UUID id = (UUID) row.get("id");
            int attempts = ((Number) row.get("attempts")).intValue();
            String eventType = (String) row.get("event_type");
            // A JDBC savepoint on the transaction's own connection: a failed attempt's writes are
            // undone while this worker keeps its lock on the receipt.
            Connection connection = DataSourceUtils.getConnection(jdbc.getDataSource());
            Savepoint savepoint = savepoint(connection);
            try {
                ReceiptHandler handler = handlers.get(eventType);
                if (handler == null) {
                    throw new PermanentProcessingException("no handler for this event type");
                }
                handler.handle(new ReceiptHandler.Receipt(id, (UUID) row.get("academy_id"), eventType,
                        ((Number) row.get("event_ts")).longValue(),
                        JsonParserFactory.getJsonParser().parseMap((String) row.get("body"))));
                jdbc.update("UPDATE zoom_webhook_receipts SET processing_status = 'PROCESSED', processed_at = ?, "
                        + "attempts = ?, last_error = NULL WHERE id = ?", Timestamp.from(DbTime.now()), attempts + 1, id);
                return Outcome.PROCESSED;
            } catch (RuntimeException e) {
                // Undo whatever the handler wrote, keep the claim, and record the failure below.
                rollbackTo(connection, savepoint);
                failure[0] = new Failure(id, eventType, attempts + 1, e);
                return recordFailure(failure[0]);
            }
        });
        return outcome;
    }

    private static Savepoint savepoint(Connection connection) {
        try {
            return connection.setSavepoint();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not set a savepoint", e);
        }
    }

    private static void rollbackTo(Connection connection, Savepoint savepoint) {
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not roll back a failed attempt", e);
        }
    }

    private record Failure(UUID id, String eventType, int attempts, RuntimeException cause) {
    }

    private Outcome recordFailure(Failure failure) {
        boolean permanent = failure.cause() instanceof PermanentProcessingException;
        boolean dead = permanent || failure.attempts() >= properties.maxAttempts();
        String reason = describe(failure.cause());
        Instant next = DbTime.now().plus(properties.backoffAfter(failure.attempts()));
        jdbc.update("UPDATE zoom_webhook_receipts SET attempts = ?, last_error = ?, processing_status = ?, next_attempt_at = ? "
                + "WHERE id = ?", failure.attempts(), reason, dead ? "DEAD" : "RECEIVED", Timestamp.from(next), failure.id());
        if (dead) {
            log.error("Zoom receipt {} ({}) dead-lettered after {} attempt(s): {}", failure.id(), failure.eventType(),
                    failure.attempts(), reason);
            return Outcome.DEAD;
        }
        log.warn("Zoom receipt {} ({}) failed attempt {}; retrying at {}: {}", failure.id(), failure.eventType(),
                failure.attempts(), next, reason);
        return Outcome.RETRY;
    }

    /** Class name plus our own message for permanent failures; other messages may quote data, so they are dropped. */
    private static String describe(RuntimeException e) {
        String text = e instanceof PermanentProcessingException ? "Permanent: " + e.getMessage() : e.getClass().getSimpleName();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
