package com.physicscube.luma.zoom;

import com.physicscube.luma.shared.time.DbTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The webhook inbox. Insertion is idempotent on the exact request body (its SHA-256, taken
 * before anything is removed). Participant network and device details that attendance never
 * needs (IP addresses, location, device, network type, MAC address) are dropped before the
 * payload is stored; the signature was already checked over the raw body.
 */
@Component
class ZoomWebhookReceipts {
    private final JdbcTemplate jdbc;

    ZoomWebhookReceipts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} if stored now, {@code false} if this exact delivery was already stored */
    @Transactional
    boolean store(UUID academyId, String eventType, long eventTs, byte[] body) {
        return jdbc.update("""
                INSERT INTO zoom_webhook_receipts (id, academy_id, event_type, event_ts, body_sha256, payload, received_at)
                VALUES (?, ?, ?, ?, ?, (?::jsonb) %s, ?)
                ON CONFLICT (academy_id, body_sha256) DO NOTHING
                """.formatted(DROPPED), UUID.randomUUID(), academyId, eventType, eventTs, sha256(body),
                new String(body, StandardCharsets.UTF_8), Timestamp.from(DbTime.now())) == 1;
    }

    /** jsonb path removals applied to every stored payload. */
    static final String DROPPED = String.join(" ", java.util.stream.Stream.of("public_ip", "private_ip", "ip_address", "location",
            "device", "network_type", "mac_addr", "internal_ip_addresses").map(f -> "#- '{payload,object,participant," + f + "}'").toList());

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
