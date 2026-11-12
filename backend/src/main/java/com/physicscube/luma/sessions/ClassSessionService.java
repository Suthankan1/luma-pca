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

    ClassSessionService(ClassSessionRepository sessions, BatchService batches, EnrollmentService enrollments) {
        this.sessions = sessions;
        this.batches = batches;
        this.enrollments = enrollments;
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
        List<com.physicscube.luma.enrollments.EnrollmentView> enrolled = enrollments.mine(caller);
        Set<UUID> batchIds = enrolled.stream().map(com.physicscube.luma.enrollments.EnrollmentView::batchId).collect(Collectors.toSet());
        if (batchIds.isEmpty()) {
            return List.of();
        }
        return sessions.inWindowForBatches(caller.academyId(), from, to, batchIds).stream()
                .map(session -> {
                    java.time.LocalDate day = session.localDate();
                    List<StudentClassView.Batch> mine = enrolled.stream()
                            .filter(e -> session.batchIds().contains(e.batchId()) && !e.enrolledOn().isAfter(day)
                                    && (e.withdrawnOn() == null || e.withdrawnOn().isAfter(day)))
                            .map(e -> new StudentClassView.Batch(e.batchId(), e.batchCode(), e.batchName()))
                            .distinct().sorted(Comparator.comparing(StudentClassView.Batch::code)).toList();
                    return mine.isEmpty() ? null : new StudentClassView(session.getId(), session.getTitle(), session.getStartsAt(),
                            session.getEndsAt(), session.getTimeZone(), day, session.getStatus(), mine);
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
        ZoneId zone = zone(timeZone);
        checkTimes(startsAt, endsAt);
        Set<UUID> wanted = checkBatches(caller, batchIds, Set.of());
        ClassSession saved = sessions.saveAndFlush(
                new ClassSession(caller.academyId(), title.strip(), startsAt, endsAt, zone, wanted, webinarId(zoomWebinarId)));
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
        return new SeriesResult(true, seriesId, saved.stream()
                .map(c -> new SeriesResult.Occurrence(c.localDate(), c.getStartsAt(), c.getEndsAt(), c.getId())).toList(), List.of());
    }

    /** Cancels the series' scheduled classes on or after {@code fromDate} (their own local dates); returns how many. */
    @Transactional
    public int cancelSeriesFrom(CurrentAccount caller, UUID seriesId, java.time.LocalDate fromDate) {
        caller.requireAcademyManager();
        List<ClassSession> series = sessions.findByAcademyIdAndSeriesIdOrderByStartsAt(caller.academyId(), seriesId);
        if (series.isEmpty()) {
            throw new ResourceNotFoundException("Class series");
        }
        List<ClassSession> cancelled = series.stream()
                .filter(c -> "SCHEDULED".equals(c.getStatus()) && !c.localDate().isBefore(fromDate)).toList();
        cancelled.forEach(ClassSession::cancel);
        sessions.saveAllAndFlush(cancelled);
        return cancelled.size();
    }

    /** Replaces every field, including the batch set. Batches being added must be active. */
    @Transactional
    public SessionView update(CurrentAccount caller, UUID sessionId, String title, Instant startsAt, Instant endsAt,
                              String timeZone, List<UUID> batchIds, String status, String zoomWebinarId,
                              long expectedVersion) {
        caller.requireAcademyManager();
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
        session.update(title.strip(), startsAt, endsAt, zone, wanted, status, webinarId(zoomWebinarId));
        return views(caller, List.of(sessions.saveAndFlush(session)), new BatchScope(true, Set.of())).get(0);
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
                s.getVersion(), s.getCreatedAt(), s.getUpdatedAt())).toList();
    }
}
