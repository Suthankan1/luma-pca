package com.physicscube.luma.batches;

import com.physicscube.luma.courses.CourseService;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batches of the caller's academy. Staff see and manage every batch of their academy;
 * students have no batch access here. A batch outside the caller's academy is reported
 * as not found.
 */
@Service
public class BatchService {
    private final BatchRepository batches;
    private final CourseService courses;

    BatchService(BatchRepository batches, CourseService courses) {
        this.batches = batches;
        this.courses = courses;
    }

    /** @param courseId {@code null} for every batch the caller may see */
    /** Batch lists per academy and course filter: the same for every staff member. */
    private record ListKey(UUID academyId, UUID courseId) {
    }

    private record Cached(List<BatchView> batches, long expiresAt) {
    }

    private static final long LIST_TTL_MS = 60_000;
    private final java.util.concurrent.ConcurrentMap<ListKey, Cached> lists = new java.util.concurrent.ConcurrentHashMap<>();

    /** Clears the academy's cached lists now and again once the change is committed, so no reader keeps the old list. */
    private void evictListsAfterCommit(UUID academyId) {
        lists.keySet().removeIf(key -> key.academyId().equals(academyId));
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            lists.keySet().removeIf(key -> key.academyId().equals(academyId));
                        }
                    });
        }
    }

    @Transactional(readOnly = true)
    public List<BatchView> list(CurrentAccount caller, UUID courseId) {
        caller.requireStaff();
        if (courseId != null) {
            courses.requireInAcademy(caller.academyId(), courseId);
        }
        // Cached for a minute and cleared by any batch change.
        ListKey key = new ListKey(caller.academyId(), courseId);
        Cached cached = lists.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expiresAt()) {
            return cached.batches();
        }
        List<Batch> found = courseId == null ? batches.findByAcademyIdOrderByStartsOnDescCodeAsc(caller.academyId())
                : batches.findByAcademyIdAndCourseIdOrderByStartsOnDescCodeAsc(caller.academyId(), courseId);
        List<BatchView> views = found.stream().map(BatchView::of).toList();
        lists.put(key, new Cached(views, now + LIST_TTL_MS));
        return views;
    }

    @Transactional(readOnly = true)
    public BatchView get(CurrentAccount caller, UUID batchId) {
        return requireReadable(caller, batchId);
    }

    /**
     * For other modules: the batch if the caller is staff of its academy, else not found.
     *
     * @throws AccessDeniedException if the caller is not staff
     */
    @Transactional(readOnly = true)
    public BatchView requireReadable(CurrentAccount caller, UUID batchId) {
        caller.requireStaff();
        return BatchView.of(load(caller.academyId(), batchId));
    }

    @Transactional
    public BatchView create(CurrentAccount caller, UUID courseId, String code, String name, LocalDate startsOn, LocalDate endsOn) {
        caller.requireStaff();
        if (!courses.requireInAcademy(caller.academyId(), courseId)) {
            throw new ConflictException("Batches cannot be added to an inactive course");
        }
        String normalized = Codes.normalize(code);
        if (batches.existsByCourseIdAndCode(courseId, normalized)) {
            throw duplicateCode();
        }
        try {
            evictListsAfterCommit(caller.academyId());
            return BatchView.of(batches.saveAndFlush(
                    new Batch(caller.academyId(), courseId, normalized, name.strip(), startsOn, endsOn)));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public BatchView update(CurrentAccount caller, UUID batchId, String code, String name, LocalDate startsOn,
                            LocalDate endsOn, boolean active, long expectedVersion) {
        caller.requireStaff();
        Batch batch = load(caller.academyId(), batchId);
        if (batch.getVersion() != expectedVersion) {
            throw new ConflictException("The record was changed by someone else; reload and retry");
        }
        batch.update(Codes.normalize(code), name.strip(), startsOn, endsOn, active);
        try {
            evictListsAfterCommit(caller.academyId());
            return BatchView.of(batches.saveAndFlush(batch));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /**
     * For other modules: the batch if it belongs to the academy, else not found. The
     * caller remains responsible for its own role checks.
     */
    @Transactional(readOnly = true)
    public BatchView requireInAcademy(UUID academyId, UUID batchId) {
        return BatchView.of(load(academyId, batchId));
    }

    /** For other modules: views of those batches that belong to the academy. No role check. */
    @Transactional(readOnly = true)
    public Map<UUID, BatchView> viewsInAcademy(UUID academyId, Collection<UUID> batchIds) {
        return batches.findByAcademyIdAndIdIn(academyId, batchIds).stream().map(BatchView::of)
                .collect(Collectors.toMap(BatchView::id, Function.identity()));
    }

    private Batch load(UUID academyId, UUID batchId) {
        return batches.findByIdAndAcademyId(batchId, academyId).orElseThrow(() -> new ResourceNotFoundException("Batch"));
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        return String.valueOf(e.getMessage()).contains("uq_batches_course_code") ? duplicateCode() : e;
    }

    private static ConflictException duplicateCode() {
        return new ConflictException("This course already has a batch with this code");
    }
}
