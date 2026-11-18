package com.physicscube.luma.sessions;

import com.physicscube.luma.batches.BatchScope;
import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.batches.BatchView;
import com.physicscube.luma.enrollments.EnrollmentService;
import com.physicscube.luma.enrollments.ExpectedStudent;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled classes. Owners and administrators manage them. Instructors and
 * attendance officers read only sessions that include a batch they are assigned
 * to, and see only those batches and their students. Anything outside the caller's
 * academy or scope is reported as not found.
 */
@Service
public class ClassSessionService {
    public static final String DEFAULT_TIME_ZONE = "Asia/Colombo";
    public static final int MAX_BATCHES = 20;
    static final Duration MAX_LENGTH = Duration.ofHours(12);
    static final Duration MAX_WINDOW = Duration.ofDays(92);
    private static final Set<String> STATUSES = Set.of("SCHEDULED", "CANCELLED");

    private final ClassSessionRepository sessions;
    private final BatchService batches;
    private final EnrollmentService enrollments;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.physicscube.luma.audit.AuditLog audit;
    @org.springframework.beans.factory.annotation.Autowired
    private ManualScheduling manualScheduling;

    ClassSessionService(ClassSessionRepository sessions, BatchService batches, EnrollmentService enrollments,
                        org.springframework.context.ApplicationEventPublisher events, com.physicscube.luma.audit.AuditLog audit) {
        this.sessions = sessions;
        this.batches = batches;
        this.enrollments = enrollments;
        this.events = events;
        this.audit = audit;
    }

    /** Timetable changes are audited: attendance is judged against the timetable. Classes hold no personal data. */
    private void audited(CurrentAccount caller, String action, String targetType, UUID targetId, Map<String, ?> details) {
        audit.record(caller.academyId(), caller.accountId(), action, targetType, targetId, details);
    }

    private static ClassSessionChanged.ZoomSlot slot(ClassSession session) {
        return session.getZoomWebinarId() == null ? null
                : new ClassSessionChanged.ZoomSlot(session.getZoomWebinarId(), session.getStartsAt(), session.getEndsAt());
    }

    private void changed(CurrentAccount caller, ClassSession session, ClassSessionChanged.ZoomSlot before) {
        events.publishEvent(new ClassSessionChanged(caller.academyId(), session.getId(), caller.accountId(), before, slot(session),
                "SCHEDULED".equals(session.getStatus())));
    }

    /** Sessions starting in [from, to), at most 92 days; {@code batchId} narrows to one readable batch. */
    @Transactional(readOnly = true)
    public List<SessionView> list(CurrentAccount caller, Instant from, Instant to, UUID batchId) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidRequestException("to must be after from and at most " + MAX_WINDOW.toDays() + " days later");
        }
        BatchScope scope = batches.scope(caller);
        List<ClassSession> found;
        if (batchId != null) {
            batches.requireReadable(caller, batchId);
            found = sessions.inWindowForBatches(caller.academyId(), from, to, Set.of(batchId));
        } else if (scope.all()) {
            found = sessions.inWindow(caller.academyId(), from, to);
        } else {
            found = scope.batchIds().isEmpty() ? List.of()
                    : sessions.inWindowForBatches(caller.academyId(), from, to, scope.batchIds());
        }
        return views(caller, found, scope);
    }

    /**
     * The calling student's classes starting in [from, to): those of a batch they are enrolled
     * in on the class's own date (the same rule as {@link #expectedStudents}). Cancelled
     * classes are included so the student sees the change.
     */
    @Transactional(readOnly = true)
    public List<StudentClassView> mine(CurrentAccount caller, Instant from, Instant to) {
        caller.requireRole("STUDENT");
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidRequestException("to must be after from and at most " + MAX_WINDOW.toDays() + " days later");
        }
        return classesFor(caller.academyId(), from, to, enrollments.mine(caller));
    }

    /**
     * A student's classes starting in [from, to), by the same rule as {@link #mine}: for owners and administrators looking
     * at one student's attendance.
     */
    @Transactional(readOnly = true)
    public List<StudentClassView> forStudent(CurrentAccount caller, UUID studentId, Instant from, Instant to) {
        caller.requireAcademyManager();
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidRequestException("to must be after from and at most " + MAX_WINDOW.toDays() + " days later");
        }
        return classesFor(caller.academyId(), from, to, enrollments.forStudent(caller, studentId));
    }

    private List<StudentClassView> classesFor(UUID academyId, Instant from, Instant to,
                                              List<com.physicscube.luma.enrollments.EnrollmentView> enrolled) {
        Set<UUID> batchIds = enrolled.stream().map(com.physicscube.luma.enrollments.EnrollmentView::batchId).collect(Collectors.toSet());
        if (batchIds.isEmpty()) {
            return List.of();
        }
        return sessions.inWindowForBatches(academyId, from, to, batchIds).stream()
                .map(session -> {
                    java.time.LocalDate day = session.localDate();
                    List<StudentClassView.Batch> mine = enrolled.stream()
                            .filter(e -> session.batchIds().contains(e.batchId()) && !e.enrolledOn().isAfter(day)
                                    && (e.withdrawnOn() == null || e.withdrawnOn().isAfter(day)))
                            .map(e -> new StudentClassView.Batch(e.batchId(), e.batchCode(), e.batchName()))
                            .distinct().sorted(Comparator.comparing(StudentClassView.Batch::code)).toList();
                    return mine.isEmpty() ? null : new StudentClassView(session.getId(), session.getTitle(), session.getStartsAt(),
                            session.getEndsAt(), session.getTimeZone(), day, session.getStatus(), mine,
                            StudentClassView.joinUrl(session.getZoomWebinarId(), session.getStatus()));
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(StudentClassView::startsAt).thenComparing(StudentClassView::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public SessionView get(CurrentAccount caller, UUID sessionId) {
        BatchScope scope = batches.scope(caller);
        return views(caller, List.of(readable(caller, sessionId, scope)), scope).get(0);
    }

    /**
     * As {@link #expectedStudents(CurrentAccount, UUID)} for a class the caller already read with {@link #get}: that view
     * lists only the batches the caller may see, so the class is not read again (one round trip fewer).
     */
    @Transactional(readOnly = true)
    public List<ExpectedStudent> expectedStudents(CurrentAccount caller, SessionView readByCaller) {
        Set<UUID> visible = readByCaller.batches().stream().map(SessionView.Batch::batchId).collect(Collectors.toSet());
        return enrollments.expectedOn(caller.academyId(), visible, readByCaller.localDate());
    }

    /** Students expected at the class, each once, limited to the batches the caller may see. */
    @Transactional(readOnly = true)
    public List<ExpectedStudent> expectedStudents(CurrentAccount caller, UUID sessionId) {
        BatchScope scope = batches.scope(caller);
        ClassSession session = readable(caller, sessionId, scope);
        Set<UUID> visible = session.batchIds().stream().filter(scope::includes).collect(Collectors.toSet());
        return enrollments.expectedOn(caller.academyId(), visible, session.localDate());
    }

    @Transactional
    public SessionView create(CurrentAccount caller, String title, Instant startsAt, Instant endsAt, String timeZone,
                              List<UUID> batchIds, String zoomWebinarId) {
        caller.requireAcademyManager();
        manualScheduling.require();
        ZoneId zone = zone(timeZone);
        checkTimes(startsAt, endsAt);
        Set<UUID> wanted = checkBatches(caller, batchIds, Set.of());
        ClassSession saved = sessions.saveAndFlush(
                new ClassSession(caller.academyId(), title.strip(), startsAt, endsAt, zone, wanted, webinarId(zoomWebinarId)));
        changed(caller, saved, null);
        audited(caller, "CLASS_SCHEDULED", "CLASS_SESSION", saved.getId(), Map.of("startsAt", saved.getStartsAt().toString(),
                "endsAt", saved.getEndsAt().toString(), "timeZone", saved.getTimeZone(), "batches", String.valueOf(saved.batchIds().size()),
                "webinar", saved.getZoomWebinarId() == null ? "none" : "set"));
        return views(caller, List.of(saved), new BatchScope(true, Set.of())).get(0);
    }

    public static final int MAX_SERIES = 100;
    static final int MAX_SERIES_SPAN_DAYS = 366;

    /**
     * Classes on the given weekdays between two dates (inclusive), at the same local time
     * in the class's zone, for the same batches and webinar; {@code skipDates} (holidays)
     * are left out. Each class is an ordinary record sharing a series id. A class that would
     * overlap a scheduled class of any of the same batches is reported and nothing is
     * created; a preview ({@code dryRun}) only reports.
     */
    @Transactional
    public SeriesResult createSeries(CurrentAccount caller, String title, java.time.LocalDate firstDate, java.time.LocalDate lastDate,
                                     List<String> weekdays, java.time.LocalTime startTime, java.time.LocalTime endTime,
                                     String timeZone, List<UUID> batchIds, String zoomWebinarId, List<java.time.LocalDate> skipDates,
                                     boolean dryRun) {
        caller.requireAcademyManager();
        manualScheduling.require();
        ZoneId zone = zone(timeZone);
        if (lastDate.isBefore(firstDate) || java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) > MAX_SERIES_SPAN_DAYS) {
            throw new InvalidRequestException("lastDate must be on or after firstDate and at most " + MAX_SERIES_SPAN_DAYS + " days later");
        }
        if (!endTime.isAfter(startTime)) {
            throw new InvalidRequestException("endTime must be after startTime on the same day");
        }
        Set<java.time.DayOfWeek> days = new HashSet<>();
        try {
            for (String day : weekdays == null ? List.<String>of() : weekdays) {
                days.add(java.time.DayOfWeek.valueOf(day.strip().toUpperCase(java.util.Locale.ROOT)));
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("weekdays must be names such as MONDAY");
        }
        if (days.isEmpty()) {
            throw new InvalidRequestException("weekdays must name at least one day");
        }
        Set<java.time.LocalDate> skip = new HashSet<>(skipDates == null ? List.of() : skipDates);
        if (skip.contains(null) || skip.size() > MAX_SERIES) {
            throw new InvalidRequestException("skipDates must list at most " + MAX_SERIES + " dates");
        }
        Set<UUID> wanted = checkBatches(caller, batchIds, Set.of());
        String webinar = webinarId(zoomWebinarId);

        List<SeriesResult.Occurrence> planned = new java.util.ArrayList<>();
        for (java.time.LocalDate day = firstDate; !day.isAfter(lastDate); day = day.plusDays(1)) {
            if (days.contains(day.getDayOfWeek()) && !skip.contains(day)) {
                Instant start = java.time.ZonedDateTime.of(day, startTime, zone).toInstant();
                Instant end = java.time.ZonedDateTime.of(day, endTime, zone).toInstant();
                checkTimes(start, end);
                planned.add(new SeriesResult.Occurrence(day, start, end, null));
            }
        }
        if (planned.isEmpty() || planned.size() > MAX_SERIES) {
            throw new InvalidRequestException("A series makes between 1 and " + MAX_SERIES + " classes; it would make " + planned.size());
        }

        // Scheduled classes of the same batches that overlap any planned class.
        Instant windowFrom = planned.get(0).startsAt().minus(MAX_LENGTH);
        Instant windowTo = planned.get(planned.size() - 1).endsAt();
        List<ClassSession> nearby = sessions.inWindowForBatches(caller.academyId(), windowFrom, windowTo, wanted).stream()
                .filter(existing -> "SCHEDULED".equals(existing.getStatus())).toList();
        List<SeriesResult.Conflict> conflicts = new java.util.ArrayList<>();
        for (SeriesResult.Occurrence occurrence : planned) {
            for (ClassSession existing : nearby) {
                if (existing.getStartsAt().isBefore(occurrence.endsAt()) && existing.getEndsAt().isAfter(occurrence.startsAt())) {
                    conflicts.add(new SeriesResult.Conflict(occurrence.localDate(), existing.getId(), existing.getTitle(),
                            existing.getStartsAt(), existing.getEndsAt()));
                }
            }
        }
        if (dryRun || !conflicts.isEmpty()) {
            return new SeriesResult(false, null, planned, conflicts);
        }
        UUID seriesId = UUID.randomUUID();
        List<ClassSession> saved = sessions.saveAllAndFlush(planned.stream()
                .map(o -> new ClassSession(caller.academyId(), title.strip(), o.startsAt(), o.endsAt(), zone, wanted, webinar, seriesId))
                .toList());
        saved.forEach(c -> changed(caller, c, null));
        audited(caller, "CLASS_SERIES_CREATED", "CLASS_SERIES", seriesId, Map.of("classes", String.valueOf(saved.size()),
                "firstDate", saved.get(0).localDate().toString(), "lastDate", saved.get(saved.size() - 1).localDate().toString(),
                "weekdays", days.stream().sorted().map(Enum::name).toList(), "timeZone", zone.getId(),
                "batches", String.valueOf(wanted.size())));
        return new SeriesResult(true, seriesId, saved.stream()
                .map(c -> new SeriesResult.Occurrence(c.localDate(), c.getStartsAt(), c.getEndsAt(), c.getId())).toList(), List.of());
    }

    /**
     * Changes the series' scheduled classes on or after {@code fromDate}: any of the title, the
     * local start and end time (kept in each class's own zone), and the webinar ID ({@code
     * clearWebinar} removes it). A class that would then overlap a scheduled class of the same
     * batches outside the change is reported and nothing changes; {@code dryRun} only previews.
     * Each class stays its own record (Zoom links are re-checked per class); one audit record
     * describes the change.
     */
    @Transactional
    public SeriesEditResult editSeriesFrom(CurrentAccount caller, UUID seriesId, java.time.LocalDate fromDate, String title,
                                           java.time.LocalTime startTime, java.time.LocalTime endTime, String zoomWebinarId,
                                           boolean clearWebinar, boolean dryRun) {
        caller.requireAcademyManager();
        manualScheduling.require();
        String newTitle = title == null || title.isBlank() ? null : title.strip();
        if (newTitle != null && newTitle.length() > 200) {
            throw new InvalidRequestException("title must be at most 200 characters");
        }
        if ((startTime == null) != (endTime == null)) {
            throw new InvalidRequestException("startTime and endTime go together");
        }
        if (startTime != null && !endTime.isAfter(startTime)) {
            throw new InvalidRequestException("endTime must be after startTime on the same day");
        }
        if (clearWebinar && zoomWebinarId != null && !zoomWebinarId.isBlank()) {
            throw new InvalidRequestException("Give a webinar ID or clear it, not both");
        }
        String newWebinar = clearWebinar ? null : webinarId(zoomWebinarId);
        boolean changeWebinar = clearWebinar || newWebinar != null;
        if (newTitle == null && startTime == null && !changeWebinar) {
            throw new InvalidRequestException("Change at least one of title, time or webinar");
        }
        List<ClassSession> series = sessions.findByAcademyIdAndSeriesIdOrderByStartsAt(caller.academyId(), seriesId);
        if (series.isEmpty()) {
            throw new ResourceNotFoundException("Class series");
        }
        List<ClassSession> affected = series.stream()
                .filter(c -> "SCHEDULED".equals(c.getStatus()) && !c.localDate().isBefore(fromDate)).toList();
        if (affected.isEmpty()) {
            throw new InvalidRequestException("This series has no scheduled classes on or after that date");
        }

        Map<UUID, Instant[]> times = new java.util.LinkedHashMap<>();
        for (ClassSession c : affected) {
            ZoneId zone = ZoneId.of(c.getTimeZone());
            Instant start = startTime == null ? c.getStartsAt() : java.time.ZonedDateTime.of(c.localDate(), startTime, zone).toInstant();
            Instant end = startTime == null ? c.getEndsAt() : java.time.ZonedDateTime.of(c.localDate(), endTime, zone).toInstant();
            checkTimes(start, end);
            times.put(c.getId(), new Instant[] {start, end});
        }
        List<SeriesResult.Conflict> conflicts = new java.util.ArrayList<>();
        if (startTime != null) {
            Set<UUID> moving = times.keySet();
            Set<UUID> batchIds = affected.stream().flatMap(c -> c.batchIds().stream()).collect(Collectors.toSet());
            Instant from = times.values().stream().map(t -> t[0]).min(Instant::compareTo).orElseThrow().minus(MAX_LENGTH);
            Instant to = times.values().stream().map(t -> t[1]).max(Instant::compareTo).orElseThrow();
            List<ClassSession> nearby = sessions.inWindowForBatches(caller.academyId(), from, to, batchIds).stream()
                    .filter(other -> "SCHEDULED".equals(other.getStatus()) && !moving.contains(other.getId())).toList();
            for (ClassSession c : affected) {
                Instant[] t = times.get(c.getId());
                for (ClassSession other : nearby) {
                    if (other.batchIds().stream().anyMatch(c.batchIds()::contains)
                            && other.getStartsAt().isBefore(t[1]) && other.getEndsAt().isAfter(t[0])) {
                        conflicts.add(new SeriesResult.Conflict(c.localDate(), other.getId(), other.getTitle(), other.getStartsAt(),
                                other.getEndsAt()));
                    }
                }
            }
        }
        List<SeriesResult.Occurrence> planned = affected.stream().map(c -> new SeriesResult.Occurrence(c.localDate(),
                times.get(c.getId())[0], times.get(c.getId())[1], c.getId())).toList();
        if (dryRun || !conflicts.isEmpty()) {
            return new SeriesEditResult(false, planned, conflicts);
        }
        Map<UUID, ClassSessionChanged.ZoomSlot> before = new java.util.HashMap<>();
        for (ClassSession c : affected) {
            before.put(c.getId(), slot(c));
            Instant[] t = times.get(c.getId());
            c.update(newTitle == null ? c.getTitle() : newTitle, t[0], t[1], ZoneId.of(c.getTimeZone()), c.batchIds(), "SCHEDULED",
                    changeWebinar ? newWebinar : c.getZoomWebinarId());
        }
        sessions.saveAllAndFlush(affected).forEach(c -> changed(caller, c, before.get(c.getId())));
        List<String> fields = new java.util.ArrayList<>();
        if (newTitle != null) {
            fields.add("title");
        }
        if (startTime != null) {
            fields.add("time");
        }
        if (changeWebinar) {
            fields.add("webinar");
        }
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("fromDate", fromDate.toString());
        details.put("classes", String.valueOf(affected.size()));
        details.put("changed", fields);
        if (startTime != null) {
            details.put("startTime", startTime.toString());
            details.put("endTime", endTime.toString());
        }
        audited(caller, "CLASS_SERIES_UPDATED", "CLASS_SERIES", seriesId, details);
        return new SeriesEditResult(true, planned, List.of());
    }

    /** Cancels the series' scheduled classes on or after {@code fromDate} (their own local dates); returns how many. */
    @Transactional
    public int cancelSeriesFrom(CurrentAccount caller, UUID seriesId, java.time.LocalDate fromDate) {
        caller.requireAcademyManager();
        manualScheduling.require();
        List<ClassSession> series = sessions.findByAcademyIdAndSeriesIdOrderByStartsAt(caller.academyId(), seriesId);
        if (series.isEmpty()) {
            throw new ResourceNotFoundException("Class series");
        }
        List<ClassSession> cancelled = series.stream()
                .filter(c -> "SCHEDULED".equals(c.getStatus()) && !c.localDate().isBefore(fromDate)).toList();
        cancelled.forEach(ClassSession::cancel);
        sessions.saveAllAndFlush(cancelled).forEach(c -> changed(caller, c, slot(c)));
        if (!cancelled.isEmpty()) {
            audited(caller, "CLASS_SERIES_CANCELLED", "CLASS_SERIES", seriesId, Map.of("fromDate", fromDate.toString(),
                    "cancelled", String.valueOf(cancelled.size())));
        }
        return cancelled.size();
    }

    /** Replaces every field, including the batch set. Batches being added must be active. */
    @Transactional
    public SessionView update(CurrentAccount caller, UUID sessionId, String title, Instant startsAt, Instant endsAt,
                              String timeZone, List<UUID> batchIds, String status, String zoomWebinarId,
                              long expectedVersion) {
        caller.requireAcademyManager();
        manualScheduling.require();
        ZoneId zone = zone(timeZone);
        checkTimes(startsAt, endsAt);
        if (!STATUSES.contains(status)) {
            throw new InvalidRequestException("status must be SCHEDULED or CANCELLED");
        }
        ClassSession session = sessions.findByIdAndAcademyId(sessionId, caller.academyId())
                .orElseThrow(() -> new ResourceNotFoundException("Class session"));
        if (session.getVersion() != expectedVersion) {
            throw new ConflictException("The record was changed by someone else; reload and retry");
        }
        Set<UUID> wanted = checkBatches(caller, batchIds, session.batchIds());
        ClassSessionChanged.ZoomSlot before = slot(session);
        String oldTitle = session.getTitle();
        Instant oldStart = session.getStartsAt();
        Instant oldEnd = session.getEndsAt();
        String oldZone = session.getTimeZone();
        String oldStatus = session.getStatus();
        String oldWebinar = session.getZoomWebinarId();
        Set<UUID> oldBatches = Set.copyOf(session.batchIds());
        session.update(title.strip(), startsAt, endsAt, zone, wanted, status, webinarId(zoomWebinarId));
        ClassSession saved = sessions.saveAndFlush(session);
        changed(caller, saved, before);
        List<String> fields = new java.util.ArrayList<>();
        if (!oldTitle.equals(saved.getTitle())) {
            fields.add("title");
        }
        if (!oldStart.equals(saved.getStartsAt()) || !oldEnd.equals(saved.getEndsAt()) || !oldZone.equals(saved.getTimeZone())) {
            fields.add("time");
        }
        if (!java.util.Objects.equals(oldWebinar, saved.getZoomWebinarId())) {
            fields.add("webinar");
        }
        if (!oldBatches.equals(saved.batchIds())) {
            fields.add("batches");
        }
        String action = oldStatus.equals(saved.getStatus()) ? (fields.isEmpty() ? null : "CLASS_UPDATED")
                : "CANCELLED".equals(saved.getStatus()) ? "CLASS_CANCELLED" : "CLASS_RESTORED";
        if (action != null) {
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("changed", fields);
            if (fields.contains("time")) {
                details.put("startsAtBefore", oldStart.toString());
                details.put("startsAt", saved.getStartsAt().toString());
                details.put("endsAt", saved.getEndsAt().toString());
                details.put("timeZone", saved.getTimeZone());
            }
            if (fields.contains("batches")) {
                details.put("batchesAdded", String.valueOf(saved.batchIds().stream().filter(b -> !oldBatches.contains(b)).count()));
                details.put("batchesRemoved", String.valueOf(oldBatches.stream().filter(b -> !saved.batchIds().contains(b)).count()));
            }
            audited(caller, action, "CLASS_SESSION", saved.getId(), details);
        }
        return views(caller, List.of(saved), new BatchScope(true, Set.of())).get(0);
    }

    /**
     * For the Zoom module: scheduled sessions on the webinar whose time, widened by
     * {@code slack} on both sides, contains {@code at}. No role check.
     */
    @Transactional(readOnly = true)
    public List<UUID> scheduledOnWebinarAround(UUID academyId, String webinarId, Instant at, Duration slack) {
        return sessions.scheduledOnWebinarAround(academyId, webinarId, at.plus(slack), at.minus(slack));
    }

    /** For other modules: the session's status if it belongs to the academy, else not found. No role check. */
    @Transactional(readOnly = true)
    public String requireStatusInAcademy(UUID academyId, UUID sessionId) {
        return sessions.findByIdAndAcademyId(sessionId, academyId).map(ClassSession::getStatus)
                .orElseThrow(() -> new ResourceNotFoundException("Class session"));
    }

    /**
     * Zoom shows webinar IDs with spaces ("812 3456 7890"); they are stored as digits only.
     * Zoom documents the ID as a string; digits-only, 9 to 20 long, is our assumption to
     * confirm against the academy's account.
     */
    static String webinarId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[\\s-]", "");
        if (!digits.matches("[0-9]{9,20}")) {
            throw new InvalidRequestException("zoomWebinarId must be a Zoom webinar ID such as 812 3456 7890");
        }
        return digits;
    }

    private ClassSession readable(CurrentAccount caller, UUID sessionId, BatchScope scope) {
        return sessions.findByIdAndAcademyId(sessionId, caller.academyId())
                .filter(session -> session.batchIds().stream().anyMatch(scope::includes))
                .orElseThrow(() -> new ResourceNotFoundException("Class session"));
    }

    private Set<UUID> checkBatches(CurrentAccount caller, List<UUID> batchIds, Set<UUID> existing) {
        if (batchIds == null || batchIds.isEmpty() || batchIds.size() > MAX_BATCHES) {
            throw new InvalidRequestException("batchIds must list between 1 and " + MAX_BATCHES + " batches");
        }
        Set<UUID> wanted = new HashSet<>(batchIds);
        if (wanted.size() != batchIds.size() || wanted.contains(null)) {
            throw new InvalidRequestException("batchIds must not repeat a batch");
        }
        for (UUID batchId : wanted) {
            BatchView batch = batches.requireInAcademy(caller.academyId(), batchId);
            if (!batch.active() && !existing.contains(batchId)) {
                throw new ConflictException("An inactive batch cannot be added to a class");
            }
        }
        return wanted;
    }

    private static void checkTimes(Instant startsAt, Instant endsAt) {
        if (!endsAt.isAfter(startsAt) || Duration.between(startsAt, endsAt).compareTo(MAX_LENGTH) > 0) {
            throw new InvalidRequestException("endsAt must be after startsAt and at most " + MAX_LENGTH.toHours() + " hours later");
        }
    }

    private static ZoneId zone(String timeZone) {
        try {
            ZoneId zone = ZoneId.of(timeZone == null || timeZone.isBlank() ? DEFAULT_TIME_ZONE : timeZone.strip());
            if (zone instanceof ZoneOffset) {
                throw new DateTimeException("fixed offset");
            }
            return zone;
        } catch (DateTimeException e) {
            throw new InvalidRequestException("timeZone must be an IANA region such as Asia/Colombo");
        }
    }

    private List<SessionView> views(CurrentAccount caller, List<ClassSession> found, BatchScope scope) {
        Map<UUID, BatchView> batchViews = batches.viewsInAcademy(caller.academyId(),
                found.stream().flatMap(s -> s.batchIds().stream()).filter(scope::includes).collect(Collectors.toSet()));
        return found.stream().map(s -> new SessionView(s.getId(), s.getTitle(), s.getStartsAt(), s.getEndsAt(),
                s.getTimeZone(), s.localDate(), s.getStatus(), s.getZoomWebinarId(), s.getSeriesId(),
                s.batchIds().stream().filter(scope::includes).map(batchViews::get)
                        .map(b -> new SessionView.Batch(b.id(), b.courseId(), b.code(), b.name()))
                        .sorted(Comparator.comparing(SessionView.Batch::code).thenComparing(SessionView.Batch::batchId))
                        .toList(),
                s.getVersion(), s.getCreatedAt(), s.getUpdatedAt(), s.getOrigin())).toList();
    }
}
