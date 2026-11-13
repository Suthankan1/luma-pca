package com.physicscube.luma.zoom;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.enrollments.ExpectedStudent;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who the people in a Zoom webinar occurrence are. A person is identified by the email Zoom
 * reports (the same across rejoins; registration with Zoom sign-in makes it the person's own),
 * never by display name. A participant is matched to a student by, in order:
 * <ol>
 *   <li>an administrator's decision for that email (a student, or "not a student"), which
 *       applies to every webinar and is audited;</li>
 *   <li>the email being a student's LUMA sign-in email.</li>
 * </ol>
 * Anyone else stays unmatched for review. The host is listed but never matched.
 * Everything here is provisional live data: it is not attendance.
 */
@Service
public class ZoomParticipantService {
    static final int MAX_EVENTS_PER_OCCURRENCE = 50_000;
    private static final int EMAIL_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final StudentService students;
    private final ClassSessionService sessions;
    private final AuditLog audit;

    ZoomParticipantService(JdbcTemplate jdbc, StudentService students, ClassSessionService sessions, AuditLog audit) {
        this.jdbc = jdbc;
        this.students = students;
        this.sessions = sessions;
        this.audit = audit;
    }

    /** STUDENT, NOT_A_STUDENT (an administrator said so), UNMATCHED, or HOST. */
    public enum Match { STUDENT, NOT_A_STUDENT, UNMATCHED, HOST }

    /**
     * @param email {@code null} when Zoom sent none (such a person cannot be matched)
     * @param visits separate joins; a rejoin is a new visit
     * @param openVisits visits with a join but no leave yet (still in, or the leave has not arrived)
     * @param matchSource MANUAL or SIGN_IN_EMAIL when matched or marked, otherwise {@code null}
     * @param expected whether the student is expected at the linked class; {@code null} when the
     *                 occurrence is not linked to a class or the person is not a student
     */
    public record ParticipantView(String email, String displayName, int visits, int openVisits, Instant firstSeenAt,
                                  Instant lastSeenAt, Match match, String matchSource, UUID studentId, String studentNumber,
                                  String studentName, Boolean expected) {
    }

    public record OccurrenceParticipants(String occurrenceUuid, UUID classSessionId, List<ParticipantView> participants) {
    }

    /** An email seen in Zoom that matches no student and has no decision yet. */
    public record UnmatchedIdentity(String email, String displayName, int occurrences, Instant lastSeenAt) {
    }

    /** @param studentId {@code null} when marked "not a student" */
    public record IdentityDecision(String email, UUID studentId, String studentNumber, String studentName, String reason,
                                   Instant linkedAt) {
    }

    private record Person(String email, Map<String, int[]> visits, String name, Instant first, Instant last, boolean host) {
    }

    @Transactional(readOnly = true)
    public OccurrenceParticipants participants(CurrentAccount caller, String occurrenceUuid) {
        caller.requireAcademyManager();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT event_type, event_ts, visit_key, participant_email, participant_name, host
                FROM zoom_occurrence_events
                WHERE academy_id = ? AND occurrence_uuid = ?
                ORDER BY event_ts, id
                LIMIT ?
                """, caller.academyId(), occurrenceUuid, MAX_EVENTS_PER_OCCURRENCE);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Occurrence");
        }
        List<UUID> linked = jdbc.queryForList("SELECT class_session_id FROM zoom_occurrence_links WHERE academy_id = ? "
                + "AND occurrence_uuid = ? AND class_session_id IS NOT NULL", UUID.class, caller.academyId(), occurrenceUuid);
        UUID sessionId = linked.isEmpty() ? null : linked.get(0);

        Map<String, Person> people = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String visit = (String) row.get("visit_key");
            if (visit == null) {
                continue; // webinar started/ended
            }
            String email = (String) row.get("participant_email");
            String key = email != null ? "e:" + email : "v:" + visit;
            Instant at = Instant.ofEpochMilli(((Number) row.get("event_ts")).longValue());
            String name = (String) row.get("participant_name");
            Person before = people.get(key);
            Person person = before == null
                    ? new Person(email, new LinkedHashMap<>(), name, at, at, Boolean.TRUE.equals(row.get("host")))
                    : new Person(email, before.visits(), name != null ? name : before.name(), before.first(), at,
                            before.host() || Boolean.TRUE.equals(row.get("host")));
            // [joins, leaves] per visit; events are read in Zoom's time order.
            int[] counts = person.visits().computeIfAbsent(visit, v -> new int[2]);
            counts["webinar.participant_left".equals(row.get("event_type")) ? 1 : 0]++;
            people.put(key, person);
        }

        Set<String> emails = people.values().stream().filter(p -> !p.host() && p.email() != null).map(Person::email)
                .collect(Collectors.toSet());
        Map<String, Resolved> resolved = resolve(caller.academyId(), emails);
        Set<UUID> expected = sessionId == null ? Set.of() : sessions.expectedStudents(caller, sessionId).stream()
                .map(ExpectedStudent::studentId).collect(Collectors.toSet());

        List<ParticipantView> views = new ArrayList<>();
        for (Person person : people.values()) {
            int open = (int) person.visits().values().stream().filter(c -> c[0] > 0 && c[1] == 0).count();
            Resolved match = person.host() ? new Resolved(Match.HOST, null, null)
                    : person.email() == null ? new Resolved(Match.UNMATCHED, null, null)
                    : resolved.getOrDefault(person.email(), new Resolved(Match.UNMATCHED, null, null));
            StudentView student = match.student();
            views.add(new ParticipantView(person.email(), person.name(), person.visits().size(), open, person.first(), person.last(),
                    match.match(), match.source(), student == null ? null : student.id(),
                    student == null ? null : student.studentNumber(), student == null ? null : student.fullName(),
                    sessionId == null || student == null ? null : expected.contains(student.id())));
        }
        // Unmatched people first (they need review), then students, people marked not a student, the host.
        List<Match> order = List.of(Match.UNMATCHED, Match.STUDENT, Match.NOT_A_STUDENT, Match.HOST);
        views.sort(Comparator.comparing((ParticipantView v) -> order.indexOf(v.match()))
                .thenComparing(v -> v.studentNumber() == null ? "" : v.studentNumber())
                .thenComparing(v -> v.email() == null ? "~" : v.email()));
        return new OccurrenceParticipants(occurrenceUuid, sessionId, views);
    }

    /** Emails seen in Zoom (not the host) with no decision and no student sign-in match, most recently seen first. */
    @Transactional(readOnly = true)
    public List<UnmatchedIdentity> unmatched(CurrentAccount caller, int limit) {
        caller.requireAcademyManager();
        if (limit < 1 || limit > 200) {
            throw new InvalidRequestException("limit must be between 1 and 200");
        }
        List<UnmatchedIdentity> undecided = jdbc.query("""
                SELECT e.participant_email AS email,
                       (array_agg(e.participant_name ORDER BY e.event_ts DESC) FILTER (WHERE e.participant_name IS NOT NULL))[1] AS name,
                       count(DISTINCT e.occurrence_uuid) AS occurrences, max(e.event_ts) AS last_ts
                FROM zoom_occurrence_events e
                WHERE e.academy_id = ? AND e.participant_email IS NOT NULL AND NOT e.host
                  AND NOT EXISTS (SELECT 1 FROM zoom_identity_links l WHERE l.academy_id = e.academy_id AND l.email = e.participant_email)
                GROUP BY e.participant_email
                ORDER BY max(e.event_ts) DESC, e.participant_email
                """, (row, i) -> new UnmatchedIdentity(row.getString("email"), row.getString("name"), row.getInt("occurrences"),
                Instant.ofEpochMilli(row.getLong("last_ts"))), caller.academyId());
        List<UnmatchedIdentity> result = new ArrayList<>();
        for (int from = 0; from < undecided.size() && result.size() < limit; from += EMAIL_BATCH) {
            List<UnmatchedIdentity> batch = undecided.subList(from, Math.min(undecided.size(), from + EMAIL_BATCH));
            Set<String> students = this.students.bySignInEmails(caller.academyId(),
                    batch.stream().map(UnmatchedIdentity::email).toList()).keySet();
            batch.stream().filter(u -> !students.contains(u.email())).limit(limit - result.size()).forEach(result::add);
        }
        return result;
    }

    /** Every decision, newest first. */
    @Transactional(readOnly = true)
    public List<IdentityDecision> decisions(CurrentAccount caller) {
        caller.requireAcademyManager();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT email, student_id, reason, linked_at FROM zoom_identity_links "
                + "WHERE academy_id = ? ORDER BY linked_at DESC, email LIMIT 1000", caller.academyId());
        Map<UUID, StudentView> named = students.viewsInAcademy(caller.academyId(),
                rows.stream().map(r -> (UUID) r.get("student_id")).filter(java.util.Objects::nonNull).toList());
        return rows.stream().map(r -> decision(r, named)).toList();
    }

    /**
     * Records which student a Zoom email belongs to, or with {@code studentId == null} that it
     * is not a student, replacing any earlier decision. The email must have been seen in this
     * academy's Zoom events.
     */
    @Transactional
    public IdentityDecision decide(CurrentAccount caller, String email, UUID studentId, String reason) {
        caller.requireAcademyManager();
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 254 || normalized.indexOf('@') < 1) {
            throw new InvalidRequestException("A Zoom email address is required");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 300) {
            throw new InvalidRequestException("A reason of 3 to 300 characters is required");
        }
        Integer seen = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM zoom_occurrence_events WHERE academy_id = ? "
                + "AND participant_email = ? LIMIT 1) s", Integer.class, caller.academyId(), normalized);
        if (seen == null || seen == 0) {
            throw new ResourceNotFoundException("Zoom participant");
        }
        StudentView student = studentId == null ? null : students.requireInAcademy(caller.academyId(), studentId);
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT student_id FROM zoom_identity_links WHERE academy_id = ? "
                + "AND email = ? FOR UPDATE", caller.academyId(), normalized);
        Instant now = DbTime.now();
        jdbc.update("""
                INSERT INTO zoom_identity_links (academy_id, email, student_id, reason, linked_by, linked_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (academy_id, email) DO UPDATE SET student_id = EXCLUDED.student_id, reason = EXCLUDED.reason,
                  linked_by = EXCLUDED.linked_by, linked_at = EXCLUDED.linked_at
                """, caller.academyId(), normalized, studentId, why, caller.accountId(), Timestamp.from(now));
        Object before = previous.isEmpty() ? "none" : previous.get(0).get("student_id") == null ? "not a student"
                : previous.get(0).get("student_id").toString();
        audit.record(caller.academyId(), caller.accountId(), student == null ? "ZOOM_IDENTITY_MARKED_NOT_STUDENT" : "ZOOM_IDENTITY_LINKED",
                "ZOOM_IDENTITY", studentId, Map.of("email", normalized, "previous", String.valueOf(before), "reason", why));
        return new IdentityDecision(normalized, studentId, student == null ? null : student.studentNumber(),
                student == null ? null : student.fullName(), why, now);
    }

    /**
     * One visit by a matched student to an occurrence linked to the class.
     *
     * @param joinedAt {@code null} when only the leave arrived
     * @param leftAt {@code null} when no leave has arrived
     */
    public record StudentVisit(UUID studentId, String occurrenceUuid, Instant joinedAt, Instant leftAt) {
    }

    /**
     * Zoom activity of the occurrences linked to a class, for attendance.
     *
     * @param startedAt per occurrence: webinar.started, else its first event
     * @param endedAt per occurrence that has a webinar.ended event
     * @param unmatchedPeople people (not the host) matched to no student and not marked "not a student"
     */
    public record ClassActivity(List<String> occurrences, Map<String, Instant> startedAt, Map<String, Instant> endedAt,
                                List<StudentVisit> visits, int unmatchedPeople) {
    }

    private static final class Visit {
        final String email;
        Instant joined;
        Instant left;

        Visit(String email) {
            this.email = email;
        }
    }

    /** For other modules: the Zoom activity of the occurrences linked to a class. No role check; the caller checks access. */
    @Transactional(readOnly = true)
    public ClassActivity activityForClass(UUID academyId, UUID sessionId) {
        List<String> occurrences = jdbc.queryForList("SELECT occurrence_uuid FROM zoom_occurrence_links WHERE academy_id = ? "
                + "AND class_session_id = ? ORDER BY occurrence_uuid", String.class, academyId, sessionId);
        Map<String, Instant> started = new HashMap<>();
        Map<String, Instant> ended = new HashMap<>();
        Map<String, Map<String, Visit>> visitsByOccurrence = new LinkedHashMap<>();
        Set<String> hosts = new HashSet<>();
        for (String occurrence : occurrences) {
            Map<String, Visit> visits = visitsByOccurrence.computeIfAbsent(occurrence, o -> new LinkedHashMap<>());
            Instant[] first = new Instant[1];
            jdbc.query("""
                    SELECT event_type, event_ts, visit_key, participant_email, host FROM zoom_occurrence_events
                    WHERE academy_id = ? AND occurrence_uuid = ? ORDER BY event_ts, id LIMIT ?
                    """, row -> {
                String type = row.getString("event_type");
                Instant at = Instant.ofEpochMilli(row.getLong("event_ts"));
                if (first[0] == null) {
                    first[0] = at;
                }
                if ("webinar.started".equals(type)) {
                    started.putIfAbsent(occurrence, at);
                } else if ("webinar.ended".equals(type)) {
                    ended.merge(occurrence, at, (a, b) -> a.isAfter(b) ? a : b);
                }
                String visitKey = row.getString("visit_key");
                String email = row.getString("participant_email");
                if (visitKey == null || email == null) {
                    return;
                }
                if (row.getBoolean("host")) {
                    hosts.add(email);
                    return;
                }
                Visit visit = visits.computeIfAbsent(visitKey, k -> new Visit(email));
                if ("webinar.participant_left".equals(type)) {
                    visit.left = visit.left == null || at.isAfter(visit.left) ? at : visit.left;
                } else if (visit.joined == null) {
                    visit.joined = at;
                }
            }, academyId, occurrence, MAX_EVENTS_PER_OCCURRENCE);
            if (first[0] != null) {
                started.putIfAbsent(occurrence, first[0]);
            }
        }
        Set<String> emails = visitsByOccurrence.values().stream().flatMap(v -> v.values().stream()).map(v -> v.email)
                .filter(e -> !hosts.contains(e)).collect(Collectors.toSet());
        Map<String, Resolved> resolved = resolve(academyId, emails);
        List<StudentVisit> matched = new ArrayList<>();
        Set<String> unmatched = new HashSet<>();
        visitsByOccurrence.forEach((occurrence, visits) -> visits.values().forEach(visit -> {
            if (hosts.contains(visit.email)) {
                return;
            }
            Resolved match = resolved.get(visit.email);
            if (match == null) {
                unmatched.add(visit.email);
            } else if (match.match() == Match.STUDENT && match.student() != null) {
                matched.add(new StudentVisit(match.student().id(), occurrence, visit.joined, visit.left));
            }
        }));
        return new ClassActivity(occurrences, started, ended, matched, unmatched.size());
    }

    private record Resolved(Match match, String source, StudentView student) {
    }

    /** Decisions first, then student sign-in emails. */
    private Map<String, Resolved> resolve(UUID academyId, Set<String> emails) {
        Map<String, Resolved> result = new HashMap<>();
        if (emails.isEmpty()) {
            return result;
        }
        List<String> all = List.copyOf(emails);
        Map<String, UUID> decided = new HashMap<>();
        Set<String> notStudents = new HashSet<>();
        for (int from = 0; from < all.size(); from += EMAIL_BATCH) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + EMAIL_BATCH));
            jdbc.query("SELECT email, student_id FROM zoom_identity_links WHERE academy_id = ? AND email = ANY (?)", row -> {
                UUID student = (UUID) row.getObject("student_id");
                if (student == null) {
                    notStudents.add(row.getString("email"));
                } else {
                    decided.put(row.getString("email"), student);
                }
            }, academyId, batch.toArray(String[]::new));
        }
        Map<UUID, StudentView> named = students.viewsInAcademy(academyId, decided.values());
        decided.forEach((email, id) -> result.put(email, new Resolved(Match.STUDENT, "MANUAL", named.get(id))));
        notStudents.forEach(email -> result.put(email, new Resolved(Match.NOT_A_STUDENT, "MANUAL", null)));
        List<String> rest = all.stream().filter(e -> !result.containsKey(e)).toList();
        for (int from = 0; from < rest.size(); from += EMAIL_BATCH) {
            students.bySignInEmails(academyId, rest.subList(from, Math.min(rest.size(), from + EMAIL_BATCH)))
                    .forEach((email, student) -> result.put(email, new Resolved(Match.STUDENT, "SIGN_IN_EMAIL", student)));
        }
        return result;
    }

    private static IdentityDecision decision(Map<String, Object> row, Map<UUID, StudentView> named) {
        UUID id = (UUID) row.get("student_id");
        StudentView student = id == null ? null : named.get(id);
        return new IdentityDecision((String) row.get("email"), id, student == null ? null : student.studentNumber(),
                student == null ? null : student.fullName(), (String) row.get("reason"),
                ((Timestamp) row.get("linked_at")).toInstant());
    }
}
