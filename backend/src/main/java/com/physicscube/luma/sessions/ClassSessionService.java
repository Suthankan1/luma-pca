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
                              List<UUID> batchIds) {
        caller.requireAcademyManager();
        ZoneId zone = zone(timeZone);
        checkTimes(startsAt, endsAt);
        Set<UUID> wanted = checkBatches(caller, batchIds, Set.of());
        ClassSession saved = sessions.saveAndFlush(
                new ClassSession(caller.academyId(), title.strip(), startsAt, endsAt, zone, wanted));
        return views(caller, List.of(saved), new BatchScope(true, Set.of())).get(0);
    }

    /** Replaces every field, including the batch set. Batches being added must be active. */
    @Transactional
    public SessionView update(CurrentAccount caller, UUID sessionId, String title, Instant startsAt, Instant endsAt,
                              String timeZone, List<UUID> batchIds, String status, long expectedVersion) {
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
        session.update(title.strip(), startsAt, endsAt, zone, wanted, status);
        return views(caller, List.of(sessions.saveAndFlush(session)), new BatchScope(true, Set.of())).get(0);
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
                s.getTimeZone(), s.localDate(), s.getStatus(),
                s.batchIds().stream().filter(scope::includes).map(batchViews::get)
                        .map(b -> new SessionView.Batch(b.id(), b.courseId(), b.code(), b.name()))
                        .sorted(Comparator.comparing(SessionView.Batch::code).thenComparing(SessionView.Batch::batchId))
                        .toList(),
                s.getVersion(), s.getCreatedAt(), s.getUpdatedAt())).toList();
    }
}
