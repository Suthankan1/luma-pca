package com.physicscube.luma.zoom;

import com.physicscube.luma.academy.AcademyClassService;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.ZoomRunSessions;
import com.physicscube.luma.shared.time.DbTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns Zoom events of a monitored webinar into attendance runs, one per occurrence UUID, whatever Zoom's schedule says.
 * The first event of a run creates it (and its attendance class); {@code webinar.started} sets the actual start (Zoom's
 * {@code start_time}, else the event time) and {@code webinar.ended} the actual end. Events may arrive twice, late or in
 * any order: the run is locked per occurrence, every update is idempotent, an ended run is never reopened, and a start or
 * end once known is only replaced by Zoom's own start/end event. Runs the attendance class's times follow (bounded by the
 * actual run, not a schedule). Students are expected only when the webinar's Academy class is known for certain.
 */
@Component
class WebinarRunTracker {
    private static final Logger log = LoggerFactory.getLogger(WebinarRunTracker.class);

    private final JdbcTemplate jdbc;
    private final ZoomRunSessions runSessions;
    private final AcademyClassService academyClasses;

    WebinarRunTracker(JdbcTemplate jdbc, ZoomRunSessions runSessions, AcademyClassService academyClasses) {
        this.jdbc = jdbc;
        this.runSessions = runSessions;
        this.academyClasses = academyClasses;
    }

    /** @return {@code false} when the webinar is not monitored (the event is left to the older class matcher) */
    boolean onEvent(UUID academyId, String webinarId, String occurrenceUuid, String eventType, long eventTs, Map<?, ?> object) {
        List<Map<String, Object>> monitors = jdbc.queryForList("""
                SELECT id, topic, time_zone, academy_class_name FROM zoom_webinar_monitors
                WHERE academy_id = ? AND webinar_id = ? AND active
                """, academyId, webinarId);
        if (monitors.isEmpty()) {
            return false;
        }
        Map<String, Object> monitor = monitors.get(0);
        Instant eventTime = Instant.ofEpochMilli(eventTs);
        // One worker at a time per run: the first event creates it, later ones only update it.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", academyId + "/" + occurrenceUuid);
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT class_session_id, first_seen_at, actual_started_at, actual_ended_at, state
                FROM zoom_webinar_runs WHERE academy_id = ? AND occurrence_uuid = ?
                """, academyId, occurrenceUuid);
        Instant now = DbTime.now();
        UUID sessionId;
        Instant firstSeen;
        Instant started;
        Instant ended;
        String state;
        if (found.isEmpty()) {
            sessionId = runSessions.create(academyId, (String) monitor.get("topic"), eventTime, zone((String) monitor.get("time_zone")),
                    webinarId, academyClasses.linkedBatch(academyId, (String) monitor.get("academy_class_name")).map(List::of).orElse(List.of()));
            firstSeen = eventTime;
            started = null;
            ended = null;
            state = "LIVE";
            jdbc.update("""
                    INSERT INTO zoom_webinar_runs (academy_id, occurrence_uuid, webinar_id, monitor_id, class_session_id, first_seen_at,
                                                   state, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'LIVE', ?, ?)
                    """, academyId, occurrenceUuid, webinarId, monitor.get("id"), sessionId, Timestamp.from(eventTime),
                    Timestamp.from(now), Timestamp.from(now));
            // The run's class is the occurrence's class; an administrator's earlier decision about it stands.
            jdbc.update("""
                    INSERT INTO zoom_occurrence_links (academy_id, occurrence_uuid, webinar_id, class_session_id, link_source, linked_at)
                    VALUES (?, ?, ?, ?, 'AUTOMATIC', ?)
                    ON CONFLICT (academy_id, occurrence_uuid) DO NOTHING
                    """, academyId, occurrenceUuid, webinarId, sessionId, Timestamp.from(now));
            log.info("Zoom webinar {} run detected ({}); attendance class created", webinarId, eventType);
        } else {
            Map<String, Object> run = found.get(0);
            sessionId = (UUID) run.get("class_session_id");
            firstSeen = ((Timestamp) run.get("first_seen_at")).toInstant();
            started = run.get("actual_started_at") == null ? null : ((Timestamp) run.get("actual_started_at")).toInstant();
            ended = run.get("actual_ended_at") == null ? null : ((Timestamp) run.get("actual_ended_at")).toInstant();
            state = (String) run.get("state");
        }
        if (eventTime.isBefore(firstSeen)) {
            firstSeen = eventTime;
        }
        switch (eventType) {
            case "webinar.started" -> started = time(object.get("start_time"), eventTime);
            case "webinar.ended" -> {
                ended = time(object.get("end_time"), eventTime);
                if (started == null && object.get("start_time") != null) {
                    started = time(object.get("start_time"), null);
                }
                state = "ENDED";
            }
            default -> {
                // Joins and leaves add evidence only (stored by the caller); they never move a run's start or end.
            }
        }
        if (started != null && ended != null && ended.isBefore(started)) {
            ended = started;
        }
        jdbc.update("""
                UPDATE zoom_webinar_runs SET first_seen_at = ?, actual_started_at = ?, actual_ended_at = ?, state = ?, updated_at = ?
                WHERE academy_id = ? AND occurrence_uuid = ?
                """, Timestamp.from(firstSeen), started == null ? null : Timestamp.from(started), ended == null ? null : Timestamp.from(ended),
                state, Timestamp.from(now), academyId, occurrenceUuid);
        Instant classStart = started != null ? started : firstSeen;
        runSessions.retime(academyId, sessionId, classStart, "ENDED".equals(state) ? (ended != null ? ended : classStart) : null);
        return true;
    }

    /** Zoom's ISO time in the event, else {@code fallback}. */
    static Instant time(Object value, Instant fallback) {
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.parse(text.strip());
            } catch (DateTimeParseException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String zone(String zoomZone) {
        if (zoomZone != null) {
            try {
                return ZoneId.of(zoomZone).getId();
            } catch (RuntimeException e) {
                // fall through
            }
        }
        return ClassSessionService.DEFAULT_TIME_ZONE;
    }
}
