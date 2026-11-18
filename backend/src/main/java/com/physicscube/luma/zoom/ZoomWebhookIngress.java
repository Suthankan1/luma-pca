package com.physicscube.luma.zoom;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Verifies and durably records Zoom webhook deliveries.
 *
 * <p>Order: signature and freshness first (401, never retried by Zoom); then shape
 * (400); then a committed receipt before any 2xx. A storage failure answers 503,
 * which Zoom retries. A redelivery of a stored body is acknowledged without a second
 * row. Logs carry the event type and outcome only, never payload, names or emails.
 */
@Service
@EnableConfigurationProperties(ZoomWebhookProperties.class)
public class ZoomWebhookIngress {
    /** Events stored for attendance. Others that pass verification are acknowledged and dropped. */
    public static final Set<String> STORED_EVENTS = Set.of(
            "webinar.started", "webinar.ended", "webinar.participant_joined", "webinar.participant_left");
    static final String URL_VALIDATION = "endpoint.url_validation";
    /**
     * plainToken may not contain ':', so the validation answer can never equal a
     * signature over "v0:<timestamp>:<body>" even if a validation request were unsigned.
     */
    private static final String PLAIN_TOKEN_SHAPE = "[A-Za-z0-9_\\-+/=]{8,128}";
    private static final Logger log = LoggerFactory.getLogger(ZoomWebhookIngress.class);

    public record Outcome(int status, Object body) {
    }

    private final ZoomWebhookProperties properties;
    private final ZoomWebhookReceipts receipts;
    private final ZoomWebhookSignature signature;

    private final ZoomReceiptSignal signal;
    private final MonitoredWebinars monitored;

    ZoomWebhookIngress(ZoomWebhookProperties properties, ZoomWebhookReceipts receipts, ZoomReceiptSignal signal,
                       MonitoredWebinars monitored) {
        this.monitored = monitored;
        this.signal = signal;
        this.properties = properties;
        this.receipts = receipts;
        this.signature = properties.enabled() ? new ZoomWebhookSignature(properties.secretToken()) : null;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public int maxBodyBytes() {
        return properties.maxBodyBytes();
    }

    /** A webinar the academy chose to monitor is collected even when the deployment's allow-list does not name it. */
    private boolean monitoredWebinar(String webinarId) {
        try {
            return monitored.isMonitored(properties.academyId(), webinarId);
        } catch (DataAccessException e) {
            return false;
        }
    }

    /** payload.object.id, which Zoom documents as a string; a number is accepted too. */
    private static String webinarId(Map<String, Object> json) {
        if (json.get("payload") instanceof Map<?, ?> payload && payload.get("object") instanceof Map<?, ?> object) {
            Object id = object.get("id");
            if (id instanceof String text) {
                return text;
            }
            if (id instanceof Number number) {
                return Long.toString(number.longValue());
            }
        }
        return "";
    }

    public Outcome receive(String timestampHeader, String signatureHeader, byte[] body) {
        ZoomWebhookSignature.Result verified = signature.verify(timestampHeader, signatureHeader, body, Instant.now(),
                properties.maxClockSkew());
        if (verified != ZoomWebhookSignature.Result.VALID) {
            log.warn("Rejected Zoom webhook: signature {}", verified);
            return new Outcome(401, null);
        }
        Map<String, Object> json;
        try {
            json = JsonParserFactory.getJsonParser().parseMap(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) { // includes JsonParseException
            log.warn("Rejected Zoom webhook: body is not a JSON object");
            return new Outcome(400, null);
        }
        if (!(json.get("event") instanceof String event) || event.length() > 100) {
            log.warn("Rejected Zoom webhook: no event type");
            return new Outcome(400, null);
        }
        if (URL_VALIDATION.equals(event)) {
            if (!(json.get("payload") instanceof Map<?, ?> payload) || !(payload.get("plainToken") instanceof String plain)
                    || !plain.matches(PLAIN_TOKEN_SHAPE)) {
                log.warn("Rejected Zoom URL validation: unexpected plainToken");
                return new Outcome(400, null);
            }
            return new Outcome(200, Map.of("plainToken", plain, "encryptedToken", signature.validationToken(plain)));
        }
        if (!STORED_EVENTS.contains(event)) {
            log.info("Acknowledged Zoom webhook of unused type {}", event.replaceAll("[^A-Za-z0-9._]", "?"));
            return new Outcome(200, null);
        }
        if (!properties.webinarAllowList().isEmpty() && !properties.webinarAllowList().contains(webinarId(json))
                && !monitoredWebinar(webinarId(json))) {
            // Not stored and the payload is not inspected further: it may describe people
            // attending a class this deployment is not allowed to collect.
            log.info("Acknowledged Zoom webhook {} for a webinar outside the allow-list; not stored", event);
            return new Outcome(200, null);
        }
        if (!(json.get("event_ts") instanceof Number eventTs) || eventTs.longValue() <= 0) {
            log.warn("Rejected Zoom webhook {}: no event_ts", event);
            return new Outcome(400, null);
        }
        try {
            boolean stored = receipts.store(properties.academyId(), event, eventTs.longValue(), body);
            log.info("Zoom webhook {} {}", event, stored ? "stored" : "already stored");
            if (stored) {
                signal.raise();
            }
            return new Outcome(200, null);
        } catch (DataAccessException e) {
            log.error("Could not store Zoom webhook {}; asking Zoom to retry", event);
            return new Outcome(503, null);
        }
    }
}
