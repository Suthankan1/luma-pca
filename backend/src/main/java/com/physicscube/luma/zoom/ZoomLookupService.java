package com.physicscube.luma.zoom;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.students.StudentView;
import com.physicscube.luma.zoom.ZoomParticipantService.Match;
import com.physicscube.luma.zoom.ZoomParticipantService.Resolved;
import com.physicscube.luma.zoom.api.ZoomApiClient;
import com.physicscube.luma.zoom.api.ZoomApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Check any of the academy's webinars by its join link or ID, including runs LUMA never received
 * live events for: list the webinar's past runs from Zoom, fetch Zoom's participant report for one
 * (stored like any other report), show who attended matched to students, and link the run to a class
 * so its attendance can be finalised there. Staff only; read-only Zoom calls.
 */
@Service
public class ZoomLookupService {
    /** Join links carry the ID after /j/, /w/, /s/, /wc/join/ or /webinar/; plain IDs may be spaced or hyphenated. */
    private static final Pattern LINK_ID = Pattern.compile("/(?:j|w|s|wc/join|webinar)/(\\d{9,20})(?:[/?#]|$)");
    private static final Pattern PLAIN_ID = Pattern.compile("\\d{9,20}");

    private final ObjectProvider<ZoomApiClient> api;
    private final ZoomReportService reports;
    private final ZoomParticipantService participants;
    private final ClassSessionService sessions;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    ZoomLookupService(ObjectProvider<ZoomApiClient> api, ZoomReportService reports, ZoomParticipantService participants,
                      ClassSessionService sessions, JdbcTemplate jdbc, AuditLog audit,
                      org.springframework.transaction.support.TransactionTemplate transactions) {
        this.transactions = transactions;
        this.api = api;
        this.reports = reports;
        this.participants = participants;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * @param reported whether Zoom's participant report for this run is stored in LUMA
     * @param linkedSessionId the class this run is linked to, if any
     */
    public record Run(String occurrenceUuid, Instant startTime, boolean reported, UUID linkedSessionId) {
    }

    public record WebinarLookup(String webinarId, String topic, Integer durationMinutes, String timezone, List<Run> runs) {
    }

    /** One person in a run, from Zoom's report: join sessions merged, never by display name. */
    public record Attendee(String email, String displayName, int joinSessions, long connectedSeconds, Instant firstJoinAt,
                           Instant lastLeaveAt, String match, String matchSource, UUID studentId, String studentNumber,
                           String studentName) {
    }

    public record RunAttendance(String webinarId, String occurrenceUuid, Instant startTime, Instant endTime, int joinSessions,
                                UUID linkedSessionId, List<Attendee> attendees) {
    }

    /** The webinar ID in a join link or typed ID, or a clear refusal. */
    public static String webinarId(String input) {
        String text = input == null ? "" : input.strip();
        String compact = text.replaceAll("[\\s-]", "");
        if (PLAIN_ID.matcher(compact).matches()) {
            return compact;
        }
        Matcher link = LINK_ID.matcher(text);
        if (text.startsWith("https://") && link.find()) {
            return link.group(1);
        }
        throw new InvalidRequestException("Paste the webinar's join link or its ID (9 to 20 digits). Registration links "
                + "(…/webinar/register/WN_…) do not contain the ID: copy the ID from the webinar's page in Zoom.");
    }

    @Transactional(readOnly = true)
    public WebinarLookup lookup(CurrentAccount caller, String input) {
        caller.requireStaff();
        String id = webinarId(input);
        ZoomApiClient client = client();
        ZoomApiClient.Webinar webinar;
        List<ZoomApiClient.PastInstance> past;
        try {
            webinar = client.webinar(id);
            past = client.pastInstances(id);
        } catch (ZoomApiException e) {
            throw zoomError(e);
        }
        Set<String> uuids = past.stream().map(ZoomApiClient.PastInstance::uuid).collect(Collectors.toSet());
        Map<String, Boolean> reported = new LinkedHashMap<>();
        Map<String, UUID> linked = new LinkedHashMap<>();
        if (!uuids.isEmpty()) {
            String[] list = uuids.toArray(String[]::new);
            jdbc.query("SELECT occurrence_uuid FROM zoom_report_fetches WHERE academy_id = ? AND status = 'FETCHED' AND occurrence_uuid = ANY (?)",
                    row -> { reported.put(row.getString(1), true); }, caller.academyId(), list);
            jdbc.query("SELECT occurrence_uuid, class_session_id FROM zoom_occurrence_links WHERE academy_id = ? AND occurrence_uuid = ANY (?) "
                    + "AND class_session_id IS NOT NULL", row -> { linked.put(row.getString(1), (UUID) row.getObject(2)); }, caller.academyId(), list);
        }
        List<Run> runs = past.stream().sorted(Comparator.comparing(ZoomApiClient.PastInstance::startTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(p -> new Run(p.uuid(), p.startTime(), reported.containsKey(p.uuid()), linked.get(p.uuid()))).toList();
        return new WebinarLookup(id, webinar.topic(), webinar.duration() > 0 ? webinar.duration() : null, webinar.timezone(), runs);
    }

    /** Fetches (or re-fetches) Zoom's participant report for one past run of the webinar. Audited. */
    public RunAttendance fetch(CurrentAccount caller, String input, String occurrenceUuid) {
        caller.requireStaff();
        String id = webinarId(input);
        requirePastRun(id, occurrenceUuid);
        ZoomReportService.FetchResult result = reports.fetch(caller.academyId(), occurrenceUuid, id);
        if (!"FETCHED".equals(result.status())) {
            throw new ConflictException("Zoom did not return the participant report (" + result.error() + "); try again shortly");
        }
        transactions.executeWithoutResult(status -> audit.record(caller.academyId(), caller.accountId(), "ZOOM_REPORT_FETCHED",
                "ZOOM_OCCURRENCE", null, Map.of("occurrenceUuid", occurrenceUuid, "webinarId", id, "status", result.status(),
                        "joinSessions", String.valueOf(result.joinSessions()), "via", "lookup")));
        return attendance(caller, id, occurrenceUuid);
    }

    /** Who attended a run, from its stored report. 404 until the report has been fetched. */
    @Transactional(readOnly = true)
    public RunAttendance attendance(CurrentAccount caller, String webinarId, String occurrenceUuid) {
        caller.requireStaff();
        Integer fetched = jdbc.queryForObject("SELECT count(*) FROM zoom_report_fetches WHERE academy_id = ? AND occurrence_uuid = ? "
                + "AND status = 'FETCHED'", Integer.class, caller.academyId(), occurrenceUuid);
        if (fetched == null || fetched == 0) {
            throw new ResourceNotFoundException("Participant report");
        }
        record Row(String email, String name, Instant joined, Instant left, boolean host, String registered) {
        }
        List<Row> rows = jdbc.query("SELECT email, display_name, joined_at, left_at, host, registrant_email FROM zoom_report_participants "
                + "WHERE academy_id = ? AND occurrence_uuid = ? ORDER BY joined_at, id", (r, i) -> new Row(r.getString(1), r.getString(2),
                r.getTimestamp(3).toInstant(), r.getTimestamp(4).toInstant(), r.getBoolean(5), r.getString(6)), caller.academyId(), occurrenceUuid);
        Map<String, List<Row>> people = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = row.email() != null ? "e:" + row.email() : "n:" + (row.name() == null ? "?" : row.name());
            people.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        Map<String, String> byEmail = new java.util.HashMap<>();
        rows.stream().filter(r -> r.email() != null && !r.host()).forEach(r -> {
            byEmail.putIfAbsent(r.email(), null);
            if (r.registered() != null) {
                byEmail.put(r.email(), r.registered());
            }
        });
        Map<String, Resolved> resolved = participants.resolveWithRegistration(caller.academyId(), byEmail);
        List<Attendee> attendees = new ArrayList<>();
        for (List<Row> visits : people.values()) {
            Row first = visits.get(0);
            boolean host = visits.stream().anyMatch(Row::host);
            Resolved match = host ? new Resolved(Match.HOST, null, null) : first.email() == null ? null : resolved.get(first.email());
            StudentView student = match == null ? null : match.student();
            attendees.add(new Attendee(first.email(), visits.get(visits.size() - 1).name(), visits.size(),
                    mergedSeconds(visits.stream().map(v -> new Instant[] {v.joined(), v.left()}).toList()), first.joined(),
                    visits.stream().map(Row::left).max(Comparator.naturalOrder()).orElse(null),
                    match == null ? Match.UNMATCHED.name() : match.match().name(), match == null ? null : match.source(),
                    student == null ? null : student.id(), student == null ? null : student.studentNumber(),
                    student == null ? null : student.fullName()));
        }
        List<String> order = List.of("UNMATCHED", "STUDENT", "NOT_A_STUDENT", "HOST");
        attendees.sort(Comparator.comparing((Attendee a) -> order.indexOf(a.match()))
                .thenComparing(a -> a.studentNumber() == null ? "" : a.studentNumber())
                .thenComparing(a -> a.email() == null ? "~" : a.email()));
        Instant start = rows.stream().map(Row::joined).min(Comparator.naturalOrder()).orElse(null);
        Instant end = rows.stream().map(Row::left).max(Comparator.naturalOrder()).orElse(null);
        UUID linked = jdbc.query("SELECT class_session_id FROM zoom_occurrence_links WHERE academy_id = ? AND occurrence_uuid = ?",
                rs -> rs.next() ? (UUID) rs.getObject(1) : null, caller.academyId(), occurrenceUuid);
        return new RunAttendance(webinarId, occurrenceUuid, start, end, rows.size(), linked, attendees);
    }

    /**
     * Links a run whose report is stored to a class (a manual, audited decision, never changed
     * automatically). The class's attendance then counts this run from Zoom's report.
     */
    @Transactional
    public RunAttendance link(CurrentAccount caller, String input, String occurrenceUuid, UUID sessionId, String reason) {
        caller.requireStaff();
        String id = webinarId(input);
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 300) {
            throw new InvalidRequestException("A reason of 3 to 300 characters is required");
        }
        Integer fetched = jdbc.queryForObject("SELECT count(*) FROM zoom_report_fetches WHERE academy_id = ? AND occurrence_uuid = ? "
                + "AND status = 'FETCHED'", Integer.class, caller.academyId(), occurrenceUuid);
        if (fetched == null || fetched == 0) {
            throw new ConflictException("Fetch Zoom's participant report for this run first");
        }
        if ("CANCELLED".equals(sessions.requireStatusInAcademy(caller.academyId(), sessionId))) {
            throw new ConflictException("A run cannot be linked to a cancelled class");
        }
        Optional<UUID> before = jdbc.query("SELECT class_session_id FROM zoom_occurrence_links WHERE academy_id = ? AND occurrence_uuid = ? FOR UPDATE",
                (rs, i) -> (UUID) rs.getObject(1), caller.academyId(), occurrenceUuid).stream().findFirst();
        jdbc.update("""
                INSERT INTO zoom_occurrence_links (academy_id, occurrence_uuid, webinar_id, class_session_id, link_source, linked_by, linked_at)
                VALUES (?, ?, ?, ?, 'MANUAL', ?, ?)
                ON CONFLICT (academy_id, occurrence_uuid) DO UPDATE SET class_session_id = EXCLUDED.class_session_id,
                  link_source = 'MANUAL', linked_by = EXCLUDED.linked_by, linked_at = EXCLUDED.linked_at
                """, caller.academyId(), occurrenceUuid, id, sessionId, caller.accountId(), Timestamp.from(DbTime.now()));
        audit.record(caller.academyId(), caller.accountId(), "ZOOM_OCCURRENCE_LINKED", "ZOOM_OCCURRENCE", sessionId, Map.of(
                "occurrenceUuid", occurrenceUuid, "previousSessionId", before.map(UUID::toString).orElse("none"),
                "previousSource", before.isPresent() ? "MANUAL" : "none", "reason", why, "via", "lookup"));
        return attendance(caller, id, occurrenceUuid);
    }

    private void requirePastRun(String webinarId, String occurrenceUuid) {
        List<ZoomApiClient.PastInstance> past;
        try {
            past = client().pastInstances(webinarId);
        } catch (ZoomApiException e) {
            throw zoomError(e);
        }
        if (past.stream().noneMatch(p -> p.uuid().equals(occurrenceUuid))) {
            throw new ResourceNotFoundException("Webinar run");
        }
    }

    private ZoomApiClient client() {
        ZoomApiClient client = api.getIfAvailable();
        if (client == null) {
            throw new ConflictException("The Zoom API is not configured for this deployment");
        }
        return client;
    }

    private static RuntimeException zoomError(ZoomApiException e) {
        if (e.notFound()) {
            return new ResourceNotFoundException("Webinar in the academy's Zoom account");
        }
        return new ConflictException("Zoom could not be reached or refused the request (" + e.getMessage() + "); try again shortly");
    }

    /** Union of [join, leave] intervals in seconds. */
    public static long mergedSeconds(List<Instant[]> intervals) {
        List<Instant[]> sorted = intervals.stream().filter(i -> i[1].isAfter(i[0])).sorted(Comparator.comparing(i -> i[0])).toList();
        long total = 0;
        Instant start = null;
        Instant end = null;
        for (Instant[] i : sorted) {
            if (start == null) {
                start = i[0];
                end = i[1];
            } else if (!i[0].isAfter(end)) {
                end = i[1].isAfter(end) ? i[1] : end;
            } else {
                total += Duration.between(start, end).toSeconds();
                start = i[0];
                end = i[1];
            }
        }
        return start == null ? total : total + Duration.between(start, end).toSeconds();
    }
}
