package com.physicscube.luma.zoom;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.zoom.api.ZoomApiClient;
import com.physicscube.luma.zoom.api.ZoomApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fetches Zoom's participant report for a webinar occurrence after it has ended and keeps
 * it, replacing any earlier copy (slice 004B). Attendance then uses the report instead of
 * live events. Zoom is called outside any database transaction; only the result is stored.
 * Report rows keep only what attendance needs: no IP addresses, devices or locations.
 */
@Service
public class ZoomReportService {
    static final int MAX_ATTEMPTS = 6;
    private static final int BATCH = 10;
    private static final Logger log = LoggerFactory.getLogger(ZoomReportService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectProvider<ZoomApiClient> api;
    private final AuditLog audit;
    private final Duration delayAfterEnd;

    private final WebinarRunTracker runs;

    ZoomReportService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectProvider<ZoomApiClient> api, AuditLog audit,
                      @Value("${luma.zoom.reports.delay-after-end:PT15M}") Duration delayAfterEnd, WebinarRunTracker runs) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.api = api;
        this.audit = audit;
        this.delayAfterEnd = delayAfterEnd;
    }

    /** @param status FETCHED or FAILED; @param error our fixed text or Zoom's status and code, when FAILED */
    public record FetchResult(String occurrenceUuid, String status, Integer joinSessions, String error) {
    }

    /** Whether report fetching can run at all (the Zoom API credentials are configured). */
    public boolean available() {
        return api.getIfAvailable() != null;
    }

    /** An owner or administrator fetches (or re-fetches) the report of an ended occurrence now. Audited. */
    public FetchResult fetchNow(CurrentAccount caller, String occurrenceUuid) {
        caller.requireStaff();
        Integer known = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM zoom_occurrence_events WHERE academy_id = ? "
                + "AND occurrence_uuid = ? LIMIT 1) s", Integer.class, caller.academyId(), occurrenceUuid);
        if (known == null || known == 0) {
            throw new ResourceNotFoundException("Occurrence");
        }
        Integer ended = jdbc.queryForObject("SELECT count(*) FROM zoom_occurrence_events WHERE academy_id = ? AND occurrence_uuid = ? "
                + "AND event_type = 'webinar.ended'", Integer.class, caller.academyId(), occurrenceUuid);
        if (ended == null || ended == 0) {
            throw new ConflictException("Zoom publishes the participant report after the webinar ends");
        }
        if (!available()) {
            throw new ConflictException("The Zoom API is not configured for this deployment");
        }
        FetchResult result = fetch(caller.academyId(), occurrenceUuid);
        transactions.executeWithoutResult(status -> audit.record(caller.academyId(), caller.accountId(), "ZOOM_REPORT_FETCHED",
                "ZOOM_OCCURRENCE", null, Map.of("occurrenceUuid", occurrenceUuid, "status", result.status(),
                        "joinSessions", String.valueOf(result.joinSessions() == null ? 0 : result.joinSessions()))));
        return result;
    }

    /**
     * Fetches reports for occurrences linked to a class that ended at least {@code delayAfterEnd}
     * ago and have no report yet, or whose last attempt failed and is due again.
     *
     * @return how many were attempted
     */
    public int runOnce() {
        if (!available()) {
            return 0;
        }
        Instant now = DbTime.now();
        // Ended occurrences linked to a class, and runs of monitored webinars Zoom never reported as ended (a missed
        // webinar.ended): those are fetched once they are older than the longest a class may last.
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT c.academy_id, c.occurrence_uuid
                FROM (
                  SELECT l.academy_id, l.occurrence_uuid, e.ended_ts AS due_ts
                  FROM zoom_occurrence_links l
                  JOIN (SELECT academy_id, occurrence_uuid, max(event_ts) AS ended_ts FROM zoom_occurrence_events
                        WHERE event_type = 'webinar.ended' GROUP BY academy_id, occurrence_uuid) e
                    ON e.academy_id = l.academy_id AND e.occurrence_uuid = l.occurrence_uuid
                  WHERE l.class_session_id IS NOT NULL AND e.ended_ts <= ?
                  UNION
                  SELECT r.academy_id, r.occurrence_uuid, (extract(epoch FROM r.first_seen_at) * 1000)::bigint
                  FROM zoom_webinar_runs r WHERE r.state = 'LIVE' AND r.first_seen_at <= ?
                ) c
                LEFT JOIN zoom_report_fetches f ON f.academy_id = c.academy_id AND f.occurrence_uuid = c.occurrence_uuid
                WHERE f.occurrence_uuid IS NULL OR (f.status = 'FAILED' AND f.attempts < ? AND f.next_attempt_at <= ?)
                ORDER BY c.due_ts
                LIMIT ?
                """, now.minus(delayAfterEnd).toEpochMilli(),
                Timestamp.from(now.minus(com.physicscube.luma.sessions.ZoomRunSessions.LIVE_SPAN).minus(delayAfterEnd)),
                MAX_ATTEMPTS, Timestamp.from(now), BATCH);
        for (Map<String, Object> row : due) {
            fetch((UUID) row.get("academy_id"), (String) row.get("occurrence_uuid"));
        }
        return due.size();
    }

    FetchResult fetch(UUID academyId, String occurrenceUuid) {
        return fetch(academyId, occurrenceUuid, null);
    }

    /** @param webinarId the webinar, when the caller knows it (a lookup); otherwise found from links or live events */
    FetchResult fetch(UUID academyId, String occurrenceUuid, String webinarId) {
        ZoomApiClient client = api.getIfAvailable();
        if (client == null) {
            return new FetchResult(occurrenceUuid, "FAILED", null, "The Zoom API is not configured");
        }
        List<ZoomApiClient.Participant> report;
        try {
            report = client.pastParticipants(occurrenceUuid);
        } catch (ZoomApiException e) {
            return failed(academyId, occurrenceUuid, truncate(e.getMessage()), e.retryAfter());
        } catch (RuntimeException e) {
            return failed(academyId, occurrenceUuid, e.getClass().getSimpleName(), null);
        }
        // The webinar (to recognise the host and read registrants): given, or known from links and live events.
        String webinar = webinarId != null ? webinarId : jdbc.query("""
                SELECT coalesce((SELECT l.webinar_id FROM zoom_occurrence_links l WHERE l.academy_id = ? AND l.occurrence_uuid = ?),
                                (SELECT min(e.webinar_id) FROM zoom_occurrence_events e WHERE e.academy_id = ? AND e.occurrence_uuid = ?))
                """, rs -> rs.next() ? rs.getString(1) : null, academyId, occurrenceUuid, academyId, occurrenceUuid);
        String hostId = null;
        Map<String, String> registrantEmails = new java.util.HashMap<>();
        if (webinar != null) {
            try {
                hostId = client.webinar(webinar).hostId();
            } catch (RuntimeException e) {
                log.warn("Zoom webinar details unavailable for host detection: {}", e.getClass().getSimpleName());
            }
            try {
                for (ZoomApiClient.Registrant r : client.registrants(webinar, null, "approved")) {
                    if (r.id() != null && r.email() != null && !r.email().isBlank()) {
                        registrantEmails.put(r.id(), r.email().strip().toLowerCase(Locale.ROOT));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Zoom registrants unavailable for matching: {}", e.getClass().getSimpleName());
            }
        }
        java.util.Set<String> knownHosts = new java.util.HashSet<>(jdbc.queryForList("SELECT DISTINCT participant_email FROM zoom_occurrence_events "
                + "WHERE academy_id = ? AND host AND participant_email IS NOT NULL", String.class, academyId));
        List<Object[]> rows = new ArrayList<>();
        for (ZoomApiClient.Participant p : report) {
            if (p.joinTime() == null || p.leaveTime() == null || p.leaveTime().isBefore(p.joinTime())) {
                continue; // incomplete row: not usable as an interval
            }
            String email = p.userEmail() == null || p.userEmail().isBlank() ? null : p.userEmail().strip().toLowerCase(Locale.ROOT);
            email = email != null && email.length() <= 254 ? email : null;
            boolean host = (hostId != null && hostId.equals(p.id())) || (email != null && knownHosts.contains(email));
            String registered = p.registrantId() == null ? null : registrantEmails.get(p.registrantId());
            rows.add(new Object[] {UUID.randomUUID(), academyId, occurrenceUuid, email, clip(p.name(), 200), clip(p.registrantId(), 100),
                    Timestamp.from(p.joinTime()), Timestamp.from(p.leaveTime()), Math.max(0, p.durationSeconds()), host,
                    registered != null && registered.length() <= 254 ? registered : null});
        }
        Instant now = DbTime.now();
        String webinarForRow = webinar;
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM zoom_report_participants WHERE academy_id = ? AND occurrence_uuid = ?", academyId, occurrenceUuid);
            jdbc.update("""
                    INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, next_attempt_at, last_error, fetched_at, join_sessions, webinar_id)
                    VALUES (?, ?, 'FETCHED', 1, NULL, NULL, ?, ?, ?)
                    ON CONFLICT (academy_id, occurrence_uuid) DO UPDATE SET status = 'FETCHED', attempts = zoom_report_fetches.attempts + 1,
                      next_attempt_at = NULL, last_error = NULL, fetched_at = EXCLUDED.fetched_at, join_sessions = EXCLUDED.join_sessions,
                      webinar_id = coalesce(EXCLUDED.webinar_id, zoom_report_fetches.webinar_id)
                    """, academyId, occurrenceUuid, Timestamp.from(now), rows.size(), webinarForRow);
            jdbc.batchUpdate("""
                    INSERT INTO zoom_report_participants (id, academy_id, occurrence_uuid, email, display_name, registrant_id,
                      joined_at, left_at, duration_seconds, host, registrant_email) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
            if (webinarForRow != null && !registrantEmails.isEmpty()) {
                List<Object[]> registrants = registrantEmails.entrySet().stream().filter(e -> e.getKey().length() <= 100 && e.getValue().length() <= 254)
                        .map(e -> new Object[] {academyId, webinarForRow, e.getKey(), e.getValue(), Timestamp.from(now)}).toList();
                jdbc.batchUpdate("""
                        INSERT INTO zoom_registrants (academy_id, webinar_id, registrant_id, email, fetched_at) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (academy_id, webinar_id, registrant_id) DO UPDATE SET email = EXCLUDED.email, fetched_at = EXCLUDED.fetched_at
                        """, registrants);
            }
            runs.reportStored(academyId, occurrenceUuid);
        });
        log.info("Zoom participant report stored for an occurrence: {} join sessions", rows.size());
        return new FetchResult(occurrenceUuid, "FETCHED", rows.size(), null);
    }

    private FetchResult failed(UUID academyId, String occurrenceUuid, String error, Duration retryAfter) {
        Instant now = DbTime.now();
        transactions.executeWithoutResult(status -> {
            Integer attempts = jdbc.query("SELECT attempts FROM zoom_report_fetches WHERE academy_id = ? AND occurrence_uuid = ? FOR UPDATE",
                    rs -> rs.next() ? rs.getInt(1) : 0, academyId, occurrenceUuid);
            int next = (attempts == null ? 0 : attempts) + 1;
            // 15 minutes doubling (to 8 hours), or Zoom's Retry-After when it is longer.
            Duration wait = Duration.ofMinutes(15L << Math.min(next - 1, 5));
            if (retryAfter != null && retryAfter.compareTo(wait) > 0) {
                wait = retryAfter;
            }
            // A failure never discards a report fetched earlier.
            jdbc.update("""
                    INSERT INTO zoom_report_fetches (academy_id, occurrence_uuid, status, attempts, next_attempt_at, last_error)
                    VALUES (?, ?, 'FAILED', 1, ?, ?)
                    ON CONFLICT (academy_id, occurrence_uuid) DO UPDATE SET attempts = zoom_report_fetches.attempts + 1,
                      next_attempt_at = EXCLUDED.next_attempt_at, last_error = EXCLUDED.last_error
                      WHERE zoom_report_fetches.status = 'FAILED'
                    """, academyId, occurrenceUuid, Timestamp.from(now.plus(wait)), error);
        });
        log.warn("Zoom participant report could not be fetched: {}", error);
        return new FetchResult(occurrenceUuid, "FAILED", null, error);
    }

    private static String clip(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String truncate(String text) {
        return text == null ? "Zoom API error" : text.length() > 300 ? text.substring(0, 300) : text;
    }
}
