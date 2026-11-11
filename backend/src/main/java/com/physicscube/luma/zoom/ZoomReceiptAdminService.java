package com.physicscube.luma.zoom;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner/administrator tools for the receipt inbox: inspect by status and replay dead letters. */
@Service
public class ZoomReceiptAdminService {
    private static final Set<String> STATUSES = Set.of("RECEIVED", "PROCESSED", "DEAD");

    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    ZoomReceiptAdminService(JdbcTemplate jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<ZoomReceiptView> list(CurrentAccount caller, String status, int limit) {
        caller.requireAcademyManager();
        if (!STATUSES.contains(status)) {
            throw new InvalidRequestException("status must be RECEIVED, PROCESSED or DEAD");
        }
        if (limit < 1 || limit > 200) {
            throw new InvalidRequestException("limit must be between 1 and 200");
        }
        return jdbc.query("""
                SELECT id, event_type, event_ts, processing_status, attempts, last_error, received_at, next_attempt_at, processed_at
                FROM zoom_webhook_receipts WHERE academy_id = ? AND processing_status = ?
                ORDER BY received_at DESC, id LIMIT ?
                """, (row, i) -> new ZoomReceiptView((UUID) row.getObject("id"), row.getString("event_type"),
                row.getLong("event_ts"), row.getString("processing_status"), row.getInt("attempts"), row.getString("last_error"),
                instant(row.getTimestamp("received_at")), instant(row.getTimestamp("next_attempt_at")),
                instant(row.getTimestamp("processed_at"))), caller.academyId(), status, limit);
    }

    /**
     * Puts a dead-lettered receipt back in the queue, due now, with a fresh attempt
     * count. The reason is recorded in the audit trail; it must not contain personal data.
     */
    @Transactional
    public void replay(CurrentAccount caller, UUID receiptId, String reason) {
        caller.requireAcademyManager();
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 300) {
            throw new InvalidRequestException("A reason of 3 to 300 characters is required");
        }
        List<String> found = jdbc.queryForList("SELECT processing_status FROM zoom_webhook_receipts "
                + "WHERE id = ? AND academy_id = ? FOR UPDATE", String.class, receiptId, caller.academyId());
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Receipt");
        }
        if (!"DEAD".equals(found.get(0))) {
            throw new ConflictException("Only a dead-lettered receipt can be replayed");
        }
        jdbc.update("UPDATE zoom_webhook_receipts SET processing_status = 'RECEIVED', attempts = 0, last_error = NULL, "
                + "next_attempt_at = ? WHERE id = ?", Timestamp.from(DbTime.now()), receiptId);
        audit.record(caller.academyId(), caller.accountId(), "ZOOM_RECEIPT_REPLAYED", "ZOOM_RECEIPT", receiptId,
                Map.of("reason", why));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
