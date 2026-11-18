package com.physicscube.luma.zoom;

import com.physicscube.luma.academy.AcademyClassService;
import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.api.ServiceUnavailableException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.zoom.api.ZoomApiClient;
import com.physicscube.luma.zoom.api.ZoomApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The webinars the academy monitors (Academy decision 2026-11-18). Staff give only a webinar ID or join link; LUMA checks
 * the academy's Zoom account can read it and copies its title and schedule from Zoom, read-only. LUMA never changes a
 * Zoom webinar: "stop monitoring" only stops LUMA collecting it. Every actual run Zoom starts then becomes its own
 * attendance run ({@link WebinarRunTracker}).
 */
@Service
public class WebinarMonitorService {
    private static final Logger log = LoggerFactory.getLogger(WebinarMonitorService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Runs listed per webinar, newest first. */
    static final int RUNS_LISTED = 20;

    private final ObjectProvider<ZoomApiClient> api;
    private final JdbcTemplate jdbc;
    private final AcademyClassService academyClasses;
    private final MonitoredWebinars monitored;
    private final AuditLog audit;
    private final com.physicscube.luma.sessions.ClassSessionService sessions;
    private final WebinarRunTracker runs;
    /** Runs already stored this recently are picked up when a webinar is added or resumed (a run lasts at most 12 h). */
    static final java.time.Duration BACKFILL = java.time.Duration.ofHours(24);

    WebinarMonitorService(ObjectProvider<ZoomApiClient> api, JdbcTemplate jdbc, AcademyClassService academyClasses,
                          MonitoredWebinars monitored, AuditLog audit, com.physicscube.luma.sessions.ClassSessionService sessions,
                          WebinarRunTracker runs) {
        this.runs = runs;
        this.sessions = sessions;
        this.api = api;
        this.jdbc = jdbc;
        this.academyClasses = academyClasses;
        this.monitored = monitored;
        this.audit = audit;
    }

    /** Zoom's own schedule entry for one occurrence of a recurring webinar (display only). */
    public record ZoomOccurrence(String occurrenceId, Instant startTime, Integer durationMinutes, String status) {
    }

    /**
     * One actual run, from Zoom's events. {@code actualStartedAt} is {@code null} until Zoom's start is known (then
     * {@code firstSeenAt} is the earliest event).
     *
     * @param connectedNow people (not the host) whose last event in this run is a join; live evidence only
     */
    public record RunView(String occurrenceUuid, UUID classSessionId, String state, Instant firstSeenAt, Instant actualStartedAt,
                          Instant actualEndedAt, boolean reported, int connectedNow, int people) {
    }

    /**
     * @param scheduledStartAt Zoom's scheduled start; {@code null} when Zoom has none ("No Zoom schedule")
     * @param classResolved whether the Academy class is known for certain; when not, runs have no expected students
     * @param classBatchId the LUMA batch the Academy class is set up as (Academy classes page); {@code null} when it is
     *                     not set up yet, so runs still have no expected students
     */
    public record MonitorView(UUID id, String webinarId, String topic, String timeZone, Instant scheduledStartAt,
                              Integer durationMinutes, boolean recurring, List<ZoomOccurrence> occurrences, String syncStatus,
                              Instant syncedAt, String syncError, String academyClassName, String classSource, boolean classResolved,
                              UUID classBatchId, boolean active, Instant addedAt, RunView live, List<RunView> runs, long version) {
    }

    /**
     * The Zoom run behind a class, for its page: Zoom's schedule (display only) beside the actual start and end.
     *
     * @param scheduledStartAt Zoom's scheduled start of the webinar when it has one (never the run's start)
     */
    public record ClassRun(String webinarId, String topic, String occurrenceUuid, String state, Instant firstSeenAt,
                           Instant actualStartedAt, Instant actualEndedAt, Instant scheduledStartAt, Integer scheduledDurationMinutes,
                           String timeZone, boolean reported, int connectedNow, int people, String academyClassName, boolean classResolved) {
    }

    /** Anyone who may open the class (the session read checks that); empty for classes not created by a Zoom run. */
    @Transactional(readOnly = true)
    public Optional<ClassRun> runForClass(CurrentAccount caller, UUID sessionId) {
        sessions.get(caller, sessionId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT monitor_id, occurrence_uuid FROM zoom_webinar_runs "
                + "WHERE academy_id = ? AND class_session_id = ?", caller.academyId(), sessionId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        UUID monitorId = (UUID) rows.get(0).get("monitor_id");
        String uuid = (String) rows.get(0).get("occurrence_uuid");
        MonitorView monitor = view(caller.academyId(), monitorId);
        RunView run = monitor.runs().stream().filter(r -> r.occurrenceUuid().equals(uuid)).findFirst()
                .orElseGet(() -> runs(caller.academyId(), monitorId, Integer.MAX_VALUE).stream()
                        .filter(r -> r.occurrenceUuid().equals(uuid)).findFirst().orElseThrow());
        return Optional.of(new ClassRun(monitor.webinarId(), monitor.topic(), uuid, run.state(), run.firstSeenAt(), run.actualStartedAt(),
                run.actualEndedAt(), monitor.scheduledStartAt(), monitor.durationMinutes(), monitor.timeZone(), run.reported(),
                run.connectedNow(), run.people(), monitor.academyClassName(), monitor.classResolved()));
    }

    /** Owners and administrators add a webinar by ID or join link. Adding one already monitored returns it. Audited. */
    @Transactional
    public MonitorView add(CurrentAccount caller, String input) {
        caller.requireAcademyManager();
        String webinarId = ZoomLookupService.webinarId(input);
        ZoomApiClient.Webinar webinar = fetch(webinarId);
        Optional<UUID> existing = idOf(caller.academyId(), webinarId);
        Instant now = DbTime.now();
        UUID id;
        if (existing.isPresent()) {
            id = existing.get();
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM zoom_webinar_monitors WHERE id = ?", Boolean.class, id))) {
                // Already monitored: refresh from Zoom and return it; nothing changed to audit.
                store(id, webinar, now);
                resolveClass(id, webinar.topic());
                return view(caller.academyId(), id);
            }
            jdbc.update("UPDATE zoom_webinar_monitors SET active = TRUE, stopped_by = NULL, stopped_at = NULL, version = version + 1 "
                    + "WHERE id = ?", id);
        } else {
            id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO zoom_webinar_monitors (id, academy_id, webinar_id, sync_status, added_by, added_at)
                    VALUES (?, ?, ?, 'OK', ?, ?)
                    ON CONFLICT (academy_id, webinar_id) DO NOTHING
                    """, id, caller.academyId(), webinarId, caller.accountId(), Timestamp.from(now));
            id = idOf(caller.academyId(), webinarId).orElseThrow();
        }
        store(id, webinar, now);
        resolveClass(id, webinar.topic());
        monitored.changed(caller.academyId());
        runs.backfill(caller.academyId(), webinarId, now.minus(BACKFILL));
        audit.record(caller.academyId(), caller.accountId(), "ZOOM_WEBINAR_MONITORED", "ZOOM_WEBINAR_MONITOR", id,
                Map.of("webinarId", webinarId));
        return view(caller.academyId(), id);
    }

    /** Copies Zoom's current title and schedule again (Zoom may have changed them). */
    @Transactional
    public MonitorView sync(CurrentAccount caller, UUID monitorId) {
        caller.requireAcademyManager();
        String webinarId = require(caller.academyId(), monitorId);
        syncOne(monitorId, webinarId);
        return view(caller.academyId(), monitorId);
    }

    /** Stops or resumes collecting a webinar in LUMA; the Zoom webinar itself is untouched. Runs and attendance are kept. */
    @Transactional
    public MonitorView setActive(CurrentAccount caller, UUID monitorId, boolean active) {
        caller.requireAcademyManager();
        require(caller.academyId(), monitorId);
        Instant now = DbTime.now();
        jdbc.update("UPDATE zoom_webinar_monitors SET active = ?, stopped_by = ?, stopped_at = ?, version = version + 1 WHERE id = ?",
                active, active ? null : caller.accountId(), active ? null : Timestamp.from(now), monitorId);
        monitored.changed(caller.academyId());
        if (active) {
            runs.backfill(caller.academyId(), require(caller.academyId(), monitorId), now.minus(BACKFILL));
        }
        audit.record(caller.academyId(), caller.accountId(), active ? "ZOOM_WEBINAR_MONITOR_RESUMED" : "ZOOM_WEBINAR_MONITOR_STOPPED",
                "ZOOM_WEBINAR_MONITOR", monitorId, Map.of());
        return view(caller.academyId(), monitorId);
    }

    /**
     * An administrator's explicit decision about which Academy class (payment class_type) the webinar teaches, or
     * {@code null} to mark it unresolved. Applies to runs that start afterwards. Audited.
     */
    @Transactional
    public MonitorView setAcademyClass(CurrentAccount caller, UUID monitorId, String className) {
        caller.requireAcademyManager();
        require(caller.academyId(), monitorId);
        String name = className == null || className.isBlank() ? null : className.strip();
        if (name != null) {
            List<String> names = academyClasses.classNames()
                    .orElseThrow(() -> new ServiceUnavailableException("The Academy database is not connected"));
            if (!names.contains(name)) {
                throw new ResourceNotFoundException("Academy class");
            }
        }
        jdbc.update("UPDATE zoom_webinar_monitors SET academy_class_name = ?, class_source = ?, version = version + 1 WHERE id = ?",
                name, name == null ? null : "ADMIN", monitorId);
        // A run going on now gets the class's students as well (only when it has none yet; ended runs are history).
        academyClasses.linkedBatch(caller.academyId(), name).ifPresent(batchId -> jdbc.update("""
                INSERT INTO session_batches (session_id, batch_id, academy_id)
                SELECT r.class_session_id, ?, r.academy_id FROM zoom_webinar_runs r
                WHERE r.academy_id = ? AND r.monitor_id = ? AND r.state = 'LIVE'
                  AND NOT EXISTS (SELECT 1 FROM session_batches b WHERE b.session_id = r.class_session_id)
                """, batchId, caller.academyId(), monitorId));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("academyClass", name == null ? "unresolved" : name);
        audit.record(caller.academyId(), caller.accountId(), "ZOOM_WEBINAR_CLASS_SET", "ZOOM_WEBINAR_MONITOR", monitorId, details);
        return view(caller.academyId(), monitorId);
    }

    /** Every monitored webinar (stopped ones too, for their history), live ones first. Owners and administrators. */
    @Transactional(readOnly = true)
    public List<MonitorView> list(CurrentAccount caller) {
        caller.requireAcademyManager();
        List<UUID> ids = jdbc.queryForList("SELECT id FROM zoom_webinar_monitors WHERE academy_id = ? ORDER BY active DESC, added_at DESC",
                UUID.class, caller.academyId());
        List<MonitorView> views = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            views.add(view(caller.academyId(), id));
        }
        views.sort((a, b) -> Boolean.compare(b.live() != null, a.live() != null));
        return views;
    }

    /** Keeps Zoom's titles and schedules current without anyone opening LUMA. Only when the Zoom API is configured. */
    @Scheduled(fixedDelayString = "${luma.zoom.monitors.sync-interval:PT30M}", initialDelayString = "${luma.zoom.monitors.sync-initial-delay:PT2M}")
    public void syncAll() {
        if (api.getIfAvailable() == null) {
            return;
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, webinar_id FROM zoom_webinar_monitors WHERE active ORDER BY synced_at NULLS FIRST LIMIT 200")) {
            try {
                syncOne((UUID) row.get("id"), (String) row.get("webinar_id"));
            } catch (RuntimeException e) {
                log.warn("Zoom webinar sync failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    void syncOne(UUID monitorId, String webinarId) {
        Instant now = DbTime.now();
        ZoomApiClient client = api.getIfAvailable();
        if (client == null) {
            throw new ServiceUnavailableException("The Zoom API is not configured for this deployment");
        }
        try {
            ZoomApiClient.Webinar webinar = client.webinar(webinarId);
            store(monitorId, webinar, now);
            resolveClass(monitorId, webinar.topic());
        } catch (ZoomApiException e) {
            // Keep the last known title and schedule, marked as not current.
            jdbc.update("UPDATE zoom_webinar_monitors SET sync_status = ?, sync_error = ? WHERE id = ?",
                    e.notFound() ? "NOT_FOUND" : "FAILED", e.notFound() ? "Zoom no longer finds this webinar"
                            : "Zoom could not be reached (" + e.getMessage() + ")", monitorId);
        }
    }

    private ZoomApiClient.Webinar fetch(String webinarId) {
        ZoomApiClient client = api.getIfAvailable();
        if (client == null) {
            throw new ServiceUnavailableException("The Zoom API is not configured for this deployment");
        }
        try {
            return client.webinar(webinarId);
        } catch (ZoomApiException e) {
            if (e.notFound()) {
                throw new ResourceNotFoundException("Webinar in the academy's Zoom account");
            }
            throw new ConflictException("Zoom could not be reached or refused the request (" + e.getMessage() + "); try again shortly");
        }
    }

    private void store(UUID monitorId, ZoomApiClient.Webinar webinar, Instant now) {
        List<Map<String, Object>> occurrences = new ArrayList<>();
        for (ZoomApiClient.Occurrence o : webinar.occurrences()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("occurrenceId", o.occurrenceId());
            item.put("startTime", o.startTime() == null ? null : o.startTime().toString());
            item.put("durationMinutes", o.duration() > 0 ? o.duration() : null);
            item.put("status", o.status());
            occurrences.add(item);
        }
        boolean recurring = webinar.type() == 6 || webinar.type() == 9;
        // For a recurring webinar with fixed times, Zoom's next occurrence is its scheduled start; type 6 has none.
        Instant scheduled = webinar.type() == 6 ? null : webinar.startTime();
        jdbc.update("""
                UPDATE zoom_webinar_monitors SET topic = ?, time_zone = ?, scheduled_start_at = ?, duration_minutes = ?, recurring = ?,
                       occurrences = ?::jsonb, host_id = ?, sync_status = 'OK', synced_at = ?, sync_error = NULL
                WHERE id = ?
                """, truncate(webinar.topic(), 300), truncate(webinar.timezone(), 64), scheduled == null ? null : Timestamp.from(scheduled),
                webinar.duration() > 0 ? webinar.duration() : null, recurring, JSON.writeValueAsString(occurrences),
                truncate(webinar.hostId(), 64), Timestamp.from(now), monitorId);
    }

    /**
     * Links the webinar to an Academy class only when its Zoom title equals exactly one Academy class name (ignoring case
     * and spacing). An administrator's choice is never replaced; no match, several matches or no Academy connection leave
     * the association as it is (unresolved when never set).
     */
    private void resolveClass(UUID monitorId, String topic) {
        String source = jdbc.queryForObject("SELECT class_source FROM zoom_webinar_monitors WHERE id = ?", String.class, monitorId);
        if ("ADMIN".equals(source) || topic == null) {
            return;
        }
        Optional<List<String>> names = academyClasses.classNames();
        if (names.isEmpty()) {
            return;
        }
        String key = normalize(topic);
        List<String> matches = names.get().stream().filter(n -> normalize(n).equals(key)).toList();
        String chosen = matches.size() == 1 ? matches.get(0) : null;
        jdbc.update("UPDATE zoom_webinar_monitors SET academy_class_name = ?, class_source = ? WHERE id = ?",
                chosen, chosen == null ? null : "EXACT_TITLE", monitorId);
    }

    static String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private Optional<UUID> idOf(UUID academyId, String webinarId) {
        return jdbc.queryForList("SELECT id FROM zoom_webinar_monitors WHERE academy_id = ? AND webinar_id = ?", UUID.class,
                academyId, webinarId).stream().findFirst();
    }

    private String require(UUID academyId, UUID monitorId) {
        return jdbc.queryForList("SELECT webinar_id FROM zoom_webinar_monitors WHERE id = ? AND academy_id = ?", String.class,
                monitorId, academyId).stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Monitored webinar"));
    }

    private MonitorView view(UUID academyId, UUID id) {
        Map<String, Object> m = jdbc.queryForMap("SELECT * FROM zoom_webinar_monitors WHERE id = ? AND academy_id = ?", id, academyId);
        List<RunView> runs = runs(academyId, id, RUNS_LISTED);
        RunView live = runs.stream().filter(r -> "LIVE".equals(r.state())).findFirst().orElse(null);
        String className = (String) m.get("academy_class_name");
        return new MonitorView(id, (String) m.get("webinar_id"), (String) m.get("topic"), (String) m.get("time_zone"),
                instant((Timestamp) m.get("scheduled_start_at")), (Integer) m.get("duration_minutes"), (Boolean) m.get("recurring"),
                occurrences(m.get("occurrences")), (String) m.get("sync_status"), instant((Timestamp) m.get("synced_at")),
                (String) m.get("sync_error"), className, (String) m.get("class_source"), className != null,
                academyClasses.linkedBatch(academyId, className).orElse(null), (Boolean) m.get("active"), instant((Timestamp) m.get("added_at")), live, runs, ((Number) m.get("version")).longValue());
    }

    private List<RunView> runs(UUID academyId, UUID monitorId, int limit) {
        return jdbc.query("""
                SELECT r.occurrence_uuid, r.class_session_id, r.state, r.first_seen_at, r.actual_started_at, r.actual_ended_at,
                       EXISTS (SELECT 1 FROM zoom_report_fetches f WHERE f.academy_id = r.academy_id AND f.occurrence_uuid = r.occurrence_uuid
                               AND f.status = 'FETCHED') AS reported,
                       (SELECT count(*) FROM (
                          SELECT DISTINCT ON (coalesce(e.participant_email, e.visit_key)) e.event_type
                          FROM zoom_occurrence_events e
                          WHERE e.academy_id = r.academy_id AND e.occurrence_uuid = r.occurrence_uuid AND NOT e.host
                            AND e.event_type IN ('webinar.participant_joined', 'webinar.participant_left')
                            AND coalesce(e.participant_email, e.visit_key) IS NOT NULL
                          ORDER BY coalesce(e.participant_email, e.visit_key), e.event_ts DESC) last
                        WHERE last.event_type = 'webinar.participant_joined') AS connected_now,
                       (SELECT count(DISTINCT coalesce(e.participant_email, e.visit_key)) FROM zoom_occurrence_events e
                        WHERE e.academy_id = r.academy_id AND e.occurrence_uuid = r.occurrence_uuid AND NOT e.host
                          AND e.event_type = 'webinar.participant_joined') AS people
                FROM zoom_webinar_runs r WHERE r.academy_id = ? AND r.monitor_id = ?
                ORDER BY coalesce(r.actual_started_at, r.first_seen_at) DESC LIMIT ?
                """, (row, i) -> new RunView(row.getString("occurrence_uuid"), (UUID) row.getObject("class_session_id"),
                row.getString("state"), instant(row.getTimestamp("first_seen_at")), instant(row.getTimestamp("actual_started_at")),
                instant(row.getTimestamp("actual_ended_at")), row.getBoolean("reported"),
                "LIVE".equals(row.getString("state")) ? row.getInt("connected_now") : 0, row.getInt("people")),
                academyId, monitorId, limit);
    }

    @SuppressWarnings("unchecked")
    private static List<ZoomOccurrence> occurrences(Object json) {
        if (json == null) {
            return List.of();
        }
        List<Map<String, Object>> items = JSON.readValue(json.toString(), List.class);
        return items.stream().map(o -> new ZoomOccurrence((String) o.get("occurrenceId"),
                o.get("startTime") == null ? null : Instant.parse((String) o.get("startTime")),
                o.get("durationMinutes") instanceof Number n ? n.intValue() : null, (String) o.get("status"))).toList();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.length() <= max ? text : text.substring(0, max);
    }

    static InvalidRequestException invalid(String message) {
        return new InvalidRequestException(message);
    }
}
