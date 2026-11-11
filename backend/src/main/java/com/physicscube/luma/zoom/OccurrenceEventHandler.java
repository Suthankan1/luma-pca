package com.physicscube.luma.zoom;

import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records each webinar event against its occurrence. Reads only the two fields Zoom
 * documents as required strings on these events: {@code payload.object.id} (webinar
 * ID) and {@code payload.object.uuid} (unique per webinar instance). Participant
 * fields are left in the receipt until a real test webinar confirms them (003D).
 */
@Component
class OccurrenceEventHandler implements ReceiptHandler {
    private final JdbcTemplate jdbc;

    OccurrenceEventHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> eventTypes() {
        return ZoomWebhookIngress.STORED_EVENTS;
    }

    @Override
    public void handle(Receipt receipt) {
        Map<?, ?> object = receipt.body().get("payload") instanceof Map<?, ?> payload && payload.get("object") instanceof Map<?, ?> o
                ? o : null;
        if (object == null) {
            throw new PermanentProcessingException("payload.object is missing");
        }
        String webinarId = identifier(object.get("id"), 40, "payload.object.id");
        String occurrence = identifier(object.get("uuid"), 100, "payload.object.uuid");
        jdbc.update("""
                INSERT INTO zoom_occurrence_events (id, academy_id, receipt_id, webinar_id, occurrence_uuid, event_type, event_ts, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (receipt_id) DO NOTHING
                """, UUID.randomUUID(), receipt.academyId(), receipt.id(), webinarId, occurrence, receipt.eventType(),
                receipt.eventTs(), Timestamp.from(DbTime.now()));
    }

    /** Zoom documents these as strings; a bare JSON number is accepted for the webinar ID. */
    private static String identifier(Object value, int maxLength, String field) {
        String text = value instanceof String s ? s
                : value instanceof Number n && n.longValue() > 0 && n.doubleValue() == n.longValue() ? Long.toString(n.longValue())
                : null;
        if (text == null || text.isBlank() || text.length() > maxLength) {
            throw new PermanentProcessingException(field + " is missing or invalid");
        }
        return text;
    }
}
