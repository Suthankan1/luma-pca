package com.physicscube.luma.engagement;

import com.physicscube.luma.audit.AuditLog;
import com.physicscube.luma.enrollments.ExpectedStudent;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.sessions.SessionView;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.shared.time.DbTime;
import com.physicscube.luma.students.StudentService;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Engagement from LUMA's own student portal (slices 006A–C), with consent.
 *
 * <ul>
 *   <li>Off until an owner switches it on, with the consent wording students will see.</li>
 *   <li>A student opts in (and, separately, into appearing by name in staff rankings); without that,
 *       nothing about them is recorded.</li>
 *   <li>Reactions (006A) and pulse answers (006B) are accepted only from students expected at the class,
 *       only while it runs (10 minutes either side), and are rate-limited.</li>
 *   <li>Rankings (006C) are staff-only, per class or over a date range, normalised per class attended;
 *       students who did not opt in are counted but not named.</li>
 * </ul>
 * Every figure names its source (PORTAL_REACTION, PORTAL_PULSE). No Zoom data is used: Zoom attendee
 * emoji are anonymous.
 */
@Service
public class EngagementService {
    static final Set<String> REACTIONS = Set.of("UNDERSTOOD", "CONFUSED", "SLOW_DOWN", "SPEED_UP");
    static final Duration CLASS_MARGIN = Duration.ofMinutes(10);
    static final Duration REACTION_GAP = Duration.ofSeconds(10);
    static final int MAX_REACTIONS_PER_CLASS = 120;
    static final int MAX_PULSES_PER_CLASS = 20;
    /** Per class, reactions count at most this much towards a ranking, so tapping repeatedly does not win. */
    static final int REACTION_SCORE_CAP = 10;

    private final JdbcTemplate jdbc;
    private final ClassSessionService sessions;
    private final StudentService students;
    private final AuditLog audit;

    EngagementService(JdbcTemplate jdbc, ClassSessionService sessions, StudentService students, AuditLog audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.students = students;
        this.audit = audit;
    }

    public record Settings(boolean enabled, String consentText, int consentVersion, long version, Instant updatedAt) {
    }

    public record MyConsent(boolean enabled, String consentText, int consentVersion, Boolean granted, boolean leaderboardOptIn,
                            boolean current) {
    }

    public record PulseView(UUID id, String prompt, List<String> options, Instant openedAt, Instant closesAt, boolean open,
                            List<Integer> counts, Integer myAnswer) {
    }

    /** @param classStartsAt / classEndsAt the class time, so the student's page knows when to stop refreshing */
    public record ClassEngagement(UUID sessionId, boolean enabled, Map<String, Integer> reactions, int reactingStudents,
                                  List<PulseView> pulses, String source, Instant classStartsAt, Instant classEndsAt) {
    }

    /** @param name {@code null} when the student did not opt in to being named */
    public record Ranked(int rank, UUID studentId, String studentNumber, String name, int classes, int reactions, int pulseAnswers,
                         double score) {
    }

    public record Ranking(int limit, int classes, int studentsCounted, List<Ranked> top, String method) {
    }

    // ---- settings (owners) -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<Settings> settings(CurrentAccount caller) {
        caller.requireAcademyManager();
        return find(caller.academyId());
    }

    @Transactional
    public Settings configure(CurrentAccount caller, boolean enabled, String consentText, Long expectedVersion) {
        if (!caller.hasRole("ACADEMY_OWNER")) {
            throw new AccessDeniedException("Only an academy owner switches engagement on or off");
        }
        String text = consentText == null ? "" : consentText.strip();
        if (text.length() < 20 || text.length() > 2000) {
            throw new InvalidRequestException("The consent wording must be 20 to 2000 characters");
        }
        Optional<Settings> before = find(caller.academyId());
        long current = before.map(Settings::version).orElse(-1L);
        if ((expectedVersion == null && before.isPresent()) || (expectedVersion != null && expectedVersion != current)) {
            throw new ConflictException("Engagement settings were changed by someone else; reload and try again");
        }
        boolean newWording = before.map(b -> !b.consentText().equals(text)).orElse(false);
        jdbc.update("""
                INSERT INTO engagement_settings (academy_id, enabled, consent_text, consent_version, updated_by, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT (academy_id) DO UPDATE SET enabled = EXCLUDED.enabled, consent_text = EXCLUDED.consent_text,
                  consent_version = engagement_settings.consent_version + CASE WHEN ? THEN 1 ELSE 0 END,
                  updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at, version = engagement_settings.version + 1
                """, caller.academyId(), enabled, text, caller.accountId(), Timestamp.from(DbTime.now()), newWording);
        Settings after = find(caller.academyId()).orElseThrow();
        audit.record(caller.academyId(), caller.accountId(), "ENGAGEMENT_SETTINGS_CHANGED", "ACADEMY", caller.academyId(), Map.of(
                "enabled", String.valueOf(enabled), "consentVersion", String.valueOf(after.consentVersion())));
        return after;
    }

    private Optional<Settings> find(UUID academyId) {
        return jdbc.query("SELECT enabled, consent_text, consent_version, version, updated_at FROM engagement_settings WHERE academy_id = ?",
                (row, i) -> new Settings(row.getBoolean(1), row.getString(2), row.getInt(3), row.getLong(4), row.getTimestamp(5).toInstant()),
                academyId).stream().findFirst();
    }

    // ---- consent (students) ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MyConsent myConsent(CurrentAccount caller) {
        UUID student = ownStudent(caller);
        Optional<Settings> settings = find(caller.academyId());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT consent_version, granted, leaderboard_opt_in FROM engagement_consents "
                + "WHERE academy_id = ? AND student_id = ?", caller.academyId(), student);
        int version = settings.map(Settings::consentVersion).orElse(0);
        boolean current = !rows.isEmpty() && ((Number) rows.get(0).get("consent_version")).intValue() == version;
        return new MyConsent(settings.map(Settings::enabled).orElse(false), settings.map(Settings::consentText).orElse(null), version,
                current ? (Boolean) rows.get(0).get("granted") : null, current && Boolean.TRUE.equals(rows.get(0).get("leaderboard_opt_in")), current);
    }

    @Transactional
    public MyConsent decide(CurrentAccount caller, boolean granted, boolean leaderboardOptIn, int consentVersion) {
        UUID student = ownStudent(caller);
        Settings settings = find(caller.academyId()).filter(Settings::enabled)
                .orElseThrow(() -> new ConflictException("Engagement is not switched on at this academy"));
        if (consentVersion != settings.consentVersion()) {
            throw new ConflictException("The consent wording has changed; read it again");
        }
        if (leaderboardOptIn && !granted) {
            throw new InvalidRequestException("Appearing in rankings needs consent to engagement first");
        }
        jdbc.update("""
                INSERT INTO engagement_consents (academy_id, student_id, consent_version, granted, leaderboard_opt_in, decided_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (academy_id, student_id) DO UPDATE SET consent_version = EXCLUDED.consent_version, granted = EXCLUDED.granted,
                  leaderboard_opt_in = EXCLUDED.leaderboard_opt_in, decided_at = EXCLUDED.decided_at
                """, caller.academyId(), student, consentVersion, granted, leaderboardOptIn, Timestamp.from(DbTime.now()));
        audit.record(caller.academyId(), caller.accountId(), granted ? "ENGAGEMENT_CONSENT_GIVEN" : "ENGAGEMENT_CONSENT_WITHDRAWN", "STUDENT",
                student, Map.of("consentVersion", String.valueOf(consentVersion), "leaderboard", String.valueOf(leaderboardOptIn)));
        return myConsent(caller);
    }

    // ---- reactions (006A) and pulse answers (006B), from students -------------------------------

    @Transactional
    public void react(CurrentAccount caller, UUID sessionId, String kind) {
        UUID student = requireParticipating(caller, sessionId);
        if (!REACTIONS.contains(kind)) {
            throw new InvalidRequestException("kind must be UNDERSTOOD, CONFUSED, SLOW_DOWN or SPEED_UP");
        }
        // Serialise one student's reactions so the gap and the cap cannot be raced.
        jdbc.queryForList("SELECT student_id FROM engagement_consents WHERE academy_id = ? AND student_id = ? FOR UPDATE", caller.academyId(), student);
        Instant now = DbTime.now();
        List<Map<String, Object>> recent = jdbc.queryForList("SELECT count(*) AS total, max(created_at) AS latest FROM portal_reactions "
                + "WHERE class_session_id = ? AND student_id = ?", sessionId, student);
        long total = ((Number) recent.get(0).get("total")).longValue();
        Timestamp latest = (Timestamp) recent.get(0).get("latest");
        if (total >= MAX_REACTIONS_PER_CLASS) {
            throw new ConflictException("You have sent the most reactions allowed for this class");
        }
        if (latest != null && latest.toInstant().plus(REACTION_GAP).isAfter(now)) {
            throw new ConflictException("Please wait a few seconds between reactions");
        }
        jdbc.update("INSERT INTO portal_reactions (id, academy_id, class_session_id, student_id, kind, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), caller.academyId(), sessionId, student, kind, Timestamp.from(now));
    }

    @Transactional
    public void answer(CurrentAccount caller, UUID sessionId, UUID questionId, int option) {
        UUID student = requireParticipating(caller, sessionId);
        List<Map<String, Object>> found = jdbc.queryForList("SELECT cardinality(options) AS n, closes_at, closed_early_at FROM pulse_questions "
                + "WHERE id = ? AND academy_id = ? AND class_session_id = ?", questionId, caller.academyId(), sessionId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Question");
        }
        Instant now = DbTime.now();
        Timestamp early = (Timestamp) found.get(0).get("closed_early_at");
        if (early != null || !((Timestamp) found.get(0).get("closes_at")).toInstant().isAfter(now)) {
            throw new ConflictException("This question has closed");
        }
        if (option < 0 || option >= ((Number) found.get(0).get("n")).intValue()) {
            throw new InvalidRequestException("Choose one of the options");
        }
        int inserted = jdbc.update("INSERT INTO pulse_answers (question_id, academy_id, student_id, option_index, answered_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (question_id, student_id) DO NOTHING", questionId, caller.academyId(), student, option, Timestamp.from(now));
        if (inserted == 0) {
            throw new ConflictException("You have already answered this question");
        }
    }

    /** What the student sees during a class: whether they can take part, and the open questions. */
    @Transactional(readOnly = true)
    public ClassEngagement forStudent(CurrentAccount caller, UUID sessionId) {
        UUID student = ownStudent(caller);
        boolean enabled = find(caller.academyId()).map(Settings::enabled).orElse(false);
        Map<String, Object> session = requireExpected(caller.academyId(), sessionId, student);
        List<PulseView> open = pulses(caller.academyId(), sessionId, student).stream().filter(PulseView::open)
                .map(p -> new PulseView(p.id(), p.prompt(), p.options(), p.openedAt(), p.closesAt(), true, null, p.myAnswer())).toList();
        return new ClassEngagement(sessionId, enabled, Map.of(), 0, open, "PORTAL",
                ((java.sql.Timestamp) session.get("starts_at")).toInstant(), ((java.sql.Timestamp) session.get("ends_at")).toInstant());
    }

    // ---- staff: pulses and class view -----------------------------------------------------------

    /** Instructors and managers who see the class open a question for 1 to 10 minutes. */
    @Transactional
    public PulseView openPulse(CurrentAccount caller, UUID sessionId, String prompt, List<String> options, int minutes) {
        if (!caller.isAcademyManager() && !caller.hasRole("INSTRUCTOR")) {
            throw new AccessDeniedException("Only instructors and managers open questions");
        }
        SessionView session = sessions.get(caller, sessionId);
        requireEnabled(caller.academyId());
        String text = prompt == null ? "" : prompt.strip();
        List<String> choices = options == null ? List.of() : options.stream().map(o -> o == null ? "" : o.strip()).toList();
        if (text.length() < 3 || text.length() > 300) {
            throw new InvalidRequestException("The question must be 3 to 300 characters");
        }
        if (choices.size() < 2 || choices.size() > 5 || choices.stream().anyMatch(o -> o.isEmpty() || o.length() > 100)
                || choices.stream().distinct().count() != choices.size()) {
            throw new InvalidRequestException("Give 2 to 5 different options of up to 100 characters");
        }
        if (minutes < 1 || minutes > 10) {
            throw new InvalidRequestException("A question stays open for 1 to 10 minutes");
        }
        Instant now = DbTime.now();
        if (!"SCHEDULED".equals(session.status()) || now.isBefore(session.startsAt().minus(CLASS_MARGIN))
                || now.isAfter(session.endsAt().plus(CLASS_MARGIN))) {
            throw new ConflictException("Questions can be opened only while the class is running");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pulse_questions WHERE class_session_id = ?", Integer.class, sessionId);
        if (count != null && count >= MAX_PULSES_PER_CLASS) {
            throw new ConflictException("This class already has the most questions allowed");
        }
        UUID id = UUID.randomUUID();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO pulse_questions (id, academy_id, class_session_id, prompt, options, opened_by, "
                    + "opened_at, closes_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            statement.setObject(1, id);
            statement.setObject(2, caller.academyId());
            statement.setObject(3, sessionId);
            statement.setString(4, text);
            statement.setArray(5, connection.createArrayOf("varchar", choices.toArray()));
            statement.setObject(6, caller.accountId());
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now.plus(Duration.ofMinutes(minutes))));
            return statement;
        });
        audit.record(caller.academyId(), caller.accountId(), "PULSE_OPENED", "CLASS_SESSION", sessionId, Map.of("minutes", String.valueOf(minutes),
                "options", String.valueOf(choices.size())));
        return pulses(caller.academyId(), sessionId, null).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void closePulse(CurrentAccount caller, UUID sessionId, UUID questionId) {
        if (!caller.isAcademyManager() && !caller.hasRole("INSTRUCTOR")) {
            throw new AccessDeniedException("Only instructors and managers close questions");
        }
        sessions.get(caller, sessionId);
        int closed = jdbc.update("UPDATE pulse_questions SET closed_early_at = ? WHERE id = ? AND academy_id = ? AND class_session_id = ? "
                + "AND closed_early_at IS NULL AND closes_at > ?", Timestamp.from(DbTime.now()), questionId, caller.academyId(), sessionId,
                Timestamp.from(DbTime.now()));
        if (closed == 0) {
            throw new ConflictException("This question is not open");
        }
    }

    /** Counts for staff who see the class: reactions by kind, how many students reacted, every question's answer counts. */
    @Transactional(readOnly = true)
    public ClassEngagement forStaff(CurrentAccount caller, UUID sessionId) {
        sessions.get(caller, sessionId);
        boolean enabled = find(caller.academyId()).map(Settings::enabled).orElse(false);
        Map<String, Integer> reactions = new LinkedHashMap<>();
        for (String kind : List.of("UNDERSTOOD", "CONFUSED", "SLOW_DOWN", "SPEED_UP")) {
            reactions.put(kind, 0);
        }
        jdbc.query("SELECT kind, count(*) FROM portal_reactions WHERE academy_id = ? AND class_session_id = ? GROUP BY kind",
                row -> { reactions.put(row.getString(1), row.getInt(2)); }, caller.academyId(), sessionId);
        Integer reacting = jdbc.queryForObject("SELECT count(DISTINCT student_id) FROM portal_reactions WHERE academy_id = ? AND class_session_id = ?",
                Integer.class, caller.academyId(), sessionId);
        SessionView view = sessions.get(caller, sessionId);
        return new ClassEngagement(sessionId, enabled, reactions, reacting == null ? 0 : reacting, pulses(caller.academyId(), sessionId, null),
                "PORTAL_REACTION, PORTAL_PULSE", view.startsAt(), view.endsAt());
    }

    // ---- 006C rankings (staff only) ----------------------------------------------------------

    /**
     * Top students over the classes of a batch in a date range (inclusive, at most 92 days): score =
     * (reactions per class, capped at 10 per class) + 3 × pulse answers, divided by the classes the
     * student was expected at. Only students who consented are counted; only those who also opted in
     * to rankings are named. Managers only.
     */
    @Transactional(readOnly = true)
    public Ranking ranking(CurrentAccount caller, UUID batchId, java.time.LocalDate from, java.time.LocalDate to, int limit) {
        return ranking(caller, java.util.Set.of(batchId), from, to, limit);
    }

    /** As the batch ranking, over every batch of a course: classes of any of them, students of any of them. */
    @Transactional(readOnly = true)
    public Ranking courseRanking(CurrentAccount caller, UUID courseId, java.time.LocalDate from, java.time.LocalDate to, int limit) {
        caller.requireAcademyManager();
        java.util.Set<UUID> batchIds = new java.util.HashSet<>(jdbc.queryForList("SELECT id FROM batches WHERE academy_id = ? AND course_id = ?",
                UUID.class, caller.academyId(), courseId));
        if (batchIds.isEmpty()) {
            throw new ResourceNotFoundException("Course");
        }
        return ranking(caller, batchIds, from, to, limit);
    }

    private Ranking ranking(CurrentAccount caller, java.util.Set<UUID> batchIds, java.time.LocalDate from, java.time.LocalDate to, int limit) {
        caller.requireAcademyManager();
        if (limit != 10 && limit != 100) {
            throw new InvalidRequestException("limit must be 10 or 100");
        }
        if (from == null || to == null || to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 92) {
            throw new InvalidRequestException("from and to are required, to not before from, at most 92 days");
        }
        Instant start = from.atStartOfDay(java.time.ZoneOffset.UTC).minusHours(14).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).plusHours(14).toInstant();
        Map<UUID, SessionView> classes = new LinkedHashMap<>();
        for (Instant piece = start; piece.isBefore(end); piece = piece.plus(Duration.ofDays(60))) {
            Instant pieceEnd = piece.plus(Duration.ofDays(60)).isBefore(end) ? piece.plus(Duration.ofDays(60)) : end;
            for (UUID batchId : batchIds) {
                sessions.list(caller, piece, pieceEnd, batchId).stream()
                        .filter(s -> "SCHEDULED".equals(s.status()) && !s.localDate().isBefore(from) && !s.localDate().isAfter(to))
                        .forEach(s -> classes.putIfAbsent(s.id(), s));
            }
        }
        Map<UUID, int[]> perStudent = new LinkedHashMap<>(); // classes expected, reactions (capped), pulse answers
        Map<UUID, String> numbers = new LinkedHashMap<>();
        for (SessionView session : classes.values()) {
            for (ExpectedStudent e : sessions.expectedStudents(caller, session.id())) {
                if (e.batchIds().stream().noneMatch(batchIds::contains)) {
                    continue;
                }
                numbers.put(e.studentId(), e.studentNumber());
                perStudent.computeIfAbsent(e.studentId(), k -> new int[3])[0]++;
            }
            jdbc.query("SELECT student_id, least(count(*), ?) FROM portal_reactions WHERE class_session_id = ? GROUP BY student_id",
                    row -> { int[] c = perStudent.get((UUID) row.getObject(1)); if (c != null) c[1] += row.getInt(2); },
                    REACTION_SCORE_CAP, session.id());
            jdbc.query("SELECT a.student_id, count(*) FROM pulse_answers a JOIN pulse_questions q ON q.id = a.question_id "
                    + "WHERE q.class_session_id = ? GROUP BY a.student_id",
                    row -> { int[] c = perStudent.get((UUID) row.getObject(1)); if (c != null) c[2] += row.getInt(2); }, session.id());
        }
        Map<UUID, Boolean> consent = new LinkedHashMap<>();
        if (!perStudent.isEmpty()) {
            jdbc.query("SELECT student_id, leaderboard_opt_in FROM engagement_consents WHERE academy_id = ? AND granted",
                    row -> { consent.put((UUID) row.getObject(1), row.getBoolean(2)); }, caller.academyId());
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        students.viewsInAcademy(caller.academyId(), consent.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList())
                .forEach((id, view) -> names.put(id, view.fullName()));
        List<Ranked> rows = new ArrayList<>();
        perStudent.forEach((id, c) -> {
            if (!consent.containsKey(id) || c[0] == 0) {
                return;
            }
            double score = Math.round((c[1] + 3.0 * c[2]) / c[0] * 100.0) / 100.0;
            rows.add(new Ranked(0, id, numbers.get(id), names.get(id), c[0], c[1], c[2], score));
        });
        rows.sort(java.util.Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::studentNumber));
        List<Ranked> top = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Ranked r = rows.get(i);
            boolean named = names.containsKey(r.studentId());
            top.add(new Ranked(i + 1, r.studentId(), named ? r.studentNumber() : null, named ? r.name() : null, r.classes(), r.reactions(),
                    r.pulseAnswers(), r.score()));
        }
        return new Ranking(limit, classes.size(), rows.size(), top, "(reactions per class, at most " + REACTION_SCORE_CAP
                + ") + 3 × pulse answers, divided by classes expected; consenting students only; sources PORTAL_REACTION, PORTAL_PULSE");
    }

    /** A ranking downloaded as CSV; audited (it can name students). @param courseId set for a course, else batchId */
    @Transactional
    public String exportRanking(CurrentAccount caller, UUID batchId, UUID courseId, java.time.LocalDate from, java.time.LocalDate to, int limit) {
        Ranking ranking = courseId != null ? courseRanking(caller, courseId, from, to, limit) : ranking(caller, batchId, from, to, limit);
        audit.record(caller.academyId(), caller.accountId(), "ENGAGEMENT_RANKING_EXPORTED", courseId != null ? "COURSE" : "BATCH",
                courseId != null ? courseId : batchId, Map.of("from", from.toString(), "to", to.toString(), "limit", String.valueOf(limit),
                        "rows", String.valueOf(ranking.top().size())));
        return rankingCsv(ranking);
    }

    /** A ranking as CSV (rank, student number, name, classes, reactions, answers, score); unnamed rows stay unnamed. */
    public static String rankingCsv(Ranking ranking) {
        StringBuilder csv = new StringBuilder("rank,student_number,student_name,classes,reactions,pulse_answers,score\r\n");
        for (Ranked r : ranking.top()) {
            csv.append(r.rank()).append(',').append(cell(r.studentNumber())).append(',').append(cell(r.name())).append(',')
                    .append(r.classes()).append(',').append(r.reactions()).append(',').append(r.pulseAnswers()).append(',').append(r.score())
                    .append("\r\n");
        }
        return csv.toString();
    }

    /** Quoted when needed; a leading formula character gets an apostrophe (as in attendance exports). */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return text.contains(",") || text.contains("\"") || text.contains("\n") || text.startsWith("'")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    // ---- internals ----------------------------------------------------------------------------

    private UUID ownStudent(CurrentAccount caller) {
        caller.requireRole("STUDENT");
        return students.idForAccount(caller.academyId(), caller.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Student record"));
    }

    private void requireEnabled(UUID academyId) {
        if (!find(academyId).map(Settings::enabled).orElse(false)) {
            throw new ConflictException("Engagement is not switched on at this academy");
        }
    }

    /** Enabled, consented to the current wording, expected at the class, and the class is running. */
    private UUID requireParticipating(CurrentAccount caller, UUID sessionId) {
        UUID student = ownStudent(caller);
        Settings settings = find(caller.academyId()).filter(Settings::enabled)
                .orElseThrow(() -> new ConflictException("Engagement is not switched on at this academy"));
        Boolean granted = jdbc.query("SELECT granted FROM engagement_consents WHERE academy_id = ? AND student_id = ? AND consent_version = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, caller.academyId(), student, settings.consentVersion());
        if (!Boolean.TRUE.equals(granted)) {
            throw new ConflictException("Turn on engagement in your portal first");
        }
        Map<String, Object> session = requireExpected(caller.academyId(), sessionId, student);
        Instant now = DbTime.now();
        if (!"SCHEDULED".equals(session.get("status")) || now.isBefore(((Timestamp) session.get("starts_at")).toInstant().minus(CLASS_MARGIN))
                || now.isAfter(((Timestamp) session.get("ends_at")).toInstant().plus(CLASS_MARGIN))) {
            throw new ConflictException("Reactions and answers are open only while the class is running");
        }
        return student;
    }

    /** The class, if the student is enrolled in one of its batches on its date; not found otherwise. */
    private Map<String, Object> requireExpected(UUID academyId, UUID sessionId, UUID student) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT s.status, s.starts_at, s.ends_at FROM class_sessions s
                WHERE s.id = ? AND s.academy_id = ? AND EXISTS (
                  SELECT 1 FROM session_batches b JOIN enrollments e ON e.batch_id = b.batch_id AND e.academy_id = s.academy_id
                  WHERE b.session_id = s.id AND e.student_id = ? AND e.enrolled_on <= (s.starts_at AT TIME ZONE s.time_zone)::date
                    AND (e.withdrawn_on IS NULL OR e.withdrawn_on > (s.starts_at AT TIME ZONE s.time_zone)::date))
                """, sessionId, academyId, student);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Class");
        }
        return found.get(0);
    }

    private List<PulseView> pulses(UUID academyId, UUID sessionId, UUID student) {
        Instant now = DbTime.now();
        return jdbc.query("""
                SELECT q.id, q.prompt, q.options, q.opened_at, q.closes_at, q.closed_early_at,
                       (SELECT array_agg(c ORDER BY i) FROM (SELECT i, (SELECT count(*) FROM pulse_answers a WHERE a.question_id = q.id
                          AND a.option_index = i) AS c FROM generate_series(0, cardinality(q.options) - 1) i) t) AS counts,
                       (SELECT a.option_index FROM pulse_answers a WHERE a.question_id = q.id AND a.student_id = ?) AS mine
                FROM pulse_questions q WHERE q.academy_id = ? AND q.class_session_id = ? ORDER BY q.opened_at
                """, (row, i) -> {
            Array options = row.getArray("options");
            Array counts = row.getArray("counts");
            boolean open = row.getTimestamp("closed_early_at") == null && row.getTimestamp("closes_at").toInstant().isAfter(now);
            List<Integer> tally = counts == null ? List.of() : Arrays.stream((Object[]) counts.getArray()).map(o -> ((Number) o).intValue()).toList();
            return new PulseView((UUID) row.getObject("id"), row.getString("prompt"), List.of((String[]) options.getArray()),
                    row.getTimestamp("opened_at").toInstant(), row.getTimestamp("closes_at").toInstant(), open, tally,
                    (Integer) row.getObject("mine"));
        }, student, academyId, sessionId);
    }
}
