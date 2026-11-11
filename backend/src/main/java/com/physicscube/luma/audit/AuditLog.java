package com.physicscube.luma.audit;

import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records sensitive actions in the caller's transaction, so an action and its audit
 * record are committed or rolled back together. Callers must never pass passwords,
 * tokens, secrets or personal data as details.
 */
@Component
public class AuditLog {
    private final JdbcTemplate jdbc;

    AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param details values must be strings or collections of strings */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID academyId, UUID actorAccountId, String action, String targetType, UUID targetId,
                       Map<String, ?> details) {
        jdbc.update("INSERT INTO audit_events (id, academy_id, actor_account_id, action, target_type, target_id, details, "
                        + "occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), academyId, actorAccountId, action, targetType, targetId, json(details),
                Timestamp.from(DbTime.now()));
    }

    static String json(Map<String, ?> details) {
        return details.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> quote(e.getKey()) + ":" + value(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String value(Object value) {
        if (value instanceof Collection<?> items) {
            return items.stream().map(String::valueOf).sorted().map(AuditLog::quote).collect(Collectors.joining(",", "[", "]"));
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
