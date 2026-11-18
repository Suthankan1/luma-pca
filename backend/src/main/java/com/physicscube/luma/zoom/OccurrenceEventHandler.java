package com.physicscube.luma.zoom;

import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records each webinar event against its occurrence: {@code payload.object.id} (webinar
 * ID) and {@code payload.object.uuid} (unique per webinar instance) are required. On
 * joined/left events it also copies who it was, in the shape the first real test webinar
 * showed: the visit ({@code participant_uuid}, else {@code user_id}; both new on every
 * rejoin), {@code registrant_id}, the email and display name, and whether it was the host
 * ({@code participant.id} equals {@code object.host_id}). Missing participant fields are
 * stored as unknown rather than failing the event. IP addresses are never copied. For a monitored webinar the event then
 * creates or updates the run of its occurrence ({@link WebinarRunTracker}); otherwise the occurrence is linked to a class
 * staff scheduled when that is unambiguous.
 */
@Component
class OccurrenceEventHandler implements ReceiptHandler {
    private final JdbcTemplate jdbc;
    private final OccurrenceLinker linker;
    private final WebinarRunTracker runs;

    OccurrenceEventHandler(JdbcTemplate jdbc, OccurrenceLinker linker, WebinarRunTracker runs) {
        this.jdbc = jdbc;
        this.linker = linker;
        this.runs = runs;
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
        Map<?, ?> participant = receipt.eventType().startsWith("webinar.participant_") && object.get("participant") instanceof Map<?, ?> p
                ? p : Map.of();
        String visit = text(participant.get("participant_uuid"), 120);
        if (visit == null) {
            String userId = text(participant.get("user_id"), 118);
            visit = userId == null ? null : "u:" + userId;
        }
        String email = text(participant.get("email"), 254);
        String participantId = text(participant.get("id"), 200);
        boolean host = participantId != null && participantId.equals(text(object.get("host_id"), 200));
        jdbc.update("""
                INSERT INTO zoom_occurrence_events (id, academy_id, receipt_id, webinar_id, occurrence_uuid, event_type, event_ts, created_at,
                  visit_key, registrant_id, participant_email, participant_name, host)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (receipt_id) DO NOTHING
                """, UUID.randomUUID(), receipt.academyId(), receipt.id(), webinarId, occurrence, receipt.eventType(),
                receipt.eventTs(), Timestamp.from(DbTime.now()), visit, text(participant.get("registrant_id"), 100),
                email == null ? null : email.toLowerCase(Locale.ROOT), text(participant.get("user_name"), 200), host);
        // A monitored webinar's runs come from Zoom's actual events (Academy decision 2026-11-18); other webinars keep the
        // older match to a class staff scheduled.
        if (!runs.onEvent(receipt.academyId(), webinarId, occurrence, receipt.eventType(), receipt.eventTs(), object)) {
            linker.tryLink(receipt.academyId(), occurrence, webinarId, java.time.Instant.ofEpochMilli(receipt.eventTs()));
        }
    }

    /** Trimmed text, or {@code null} when absent, blank or longer than {@code maxLength}. */
    private static String text(Object value, int maxLength) {
        String text = value instanceof String s ? s.strip() : value instanceof Number n ? n.toString() : null;
        return text == null || text.isEmpty() || text.length() > maxLength ? null : text;
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
