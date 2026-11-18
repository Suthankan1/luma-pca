package com.physicscube.luma.batches;

import com.physicscube.luma.courses.CourseService;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
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
 * Batches of the caller's academy. Owners and administrators see and manage every
 * batch. Instructors and attendance officers may read only the batches they are
 * assigned to in a role they still hold; their role alone grants nothing. A batch
 * outside the caller's academy or scope is reported as not found.
 */
@Service
public class BatchService {
    private final BatchRepository batches;
    private final StaffAssignmentRepository assignments;
    private final CourseService courses;

    BatchService(BatchRepository batches, StaffAssignmentRepository assignments, CourseService courses) {
        this.batches = batches;
        this.assignments = assignments;
        this.courses = courses;
    }

    /** @param courseId {@code null} for every batch the caller may see */
    /** Managers' batch lists per academy and course filter: the same for every owner and administrator. */
    private record ListKey(UUID academyId, UUID courseId) {
    }

    private record Cached(List<BatchView> batches, long expiresAt) {
    }

    private static final long LIST_TTL_MS = 60_000;
    private final java.util.concurrent.ConcurrentMap<ListKey, Cached> managerLists = new java.util.concurrent.ConcurrentHashMap<>();

    /** Clears the academy's cached lists now and again once the change is committed, so no reader keeps the old list. */
    private void evictListsAfterCommit(UUID academyId) {
        managerLists.keySet().removeIf(key -> key.academyId().equals(academyId));
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            managerLists.keySet().removeIf(key -> key.academyId().equals(academyId));
                        }
                    });
        }
    }

    @Transactional(readOnly = true)
    public List<BatchView> list(CurrentAccount caller, UUID courseId) {
        if (courseId != null) {
            courses.requireInAcademy(caller.academyId(), courseId);
        }
        List<Batch> found;
        if (caller.isAcademyManager()) {
            // Cached for a minute and cleared by any batch change; instructors' lists depend on assignments and are not.
            ListKey key = new ListKey(caller.academyId(), courseId);
            Cached cached = managerLists.get(key);
            long now = System.currentTimeMillis();
            if (cached != null && now < cached.expiresAt()) {
                return cached.batches();
            }
            found = courseId == null ? batches.findByAcademyIdOrderByStartsOnDescCodeAsc(caller.academyId())
                    : batches.findByAcademyIdAndCourseIdOrderByStartsOnDescCodeAsc(caller.academyId(), courseId);
            List<BatchView> views = found.stream().map(BatchView::of).toList();
            managerLists.put(key, new Cached(views, now + LIST_TTL_MS));
            return views;
        } else {
            found = batches.findByAcademyIdAndIdIn(caller.academyId(), assignedBatchIds(caller)).stream()
                    .filter(batch -> courseId == null || courseId.equals(batch.getCourseId()))
                    .sorted(Comparator.comparing(Batch::getStartsOn).reversed().thenComparing(Batch::getCode))
                    .toList();
        }
        return found.stream().map(BatchView::of).toList();
    }

    @Transactional(readOnly = true)
    public BatchView get(CurrentAccount caller, UUID batchId) {
        return requireReadable(caller, batchId);
    }

    /**
     * For other modules: the batch if the caller may read it (manager of its academy,
     * or assigned to it in a role they hold), else not found.
     *
     * @throws AccessDeniedException if the caller has no role that can ever read a batch
     */
    @Transactional(readOnly = true)
    public BatchView requireReadable(CurrentAccount caller, UUID batchId) {
        if (caller.isAcademyManager()) {
            return BatchView.of(load(caller.academyId(), batchId));
        }
        Set<String> scoped = caller.batchScopedRoles();
        if (scoped.isEmpty()) {
            throw new AccessDeniedException("No role that grants batch access");
        }
        Batch batch = load(caller.academyId(), batchId);
        if (!assignments.existsByBatchIdAndAccountIdAndRoleIn(batchId, caller.accountId(), scoped)) {
            throw new ResourceNotFoundException("Batch");
        }
        return BatchView.of(batch);
    }

    /**
     * For other modules: the batches the caller may read.
     *
     * @throws AccessDeniedException if the caller has no role that can ever read a batch
     */
    @Transactional(readOnly = true)
    public BatchScope scope(CurrentAccount caller) {
        return caller.isAcademyManager() ? new BatchScope(true, Set.of())
                : new BatchScope(false, Set.copyOf(assignedBatchIds(caller)));
    }

    private List<UUID> assignedBatchIds(CurrentAccount caller) {
        Set<String> scoped = caller.batchScopedRoles();
        if (scoped.isEmpty()) {
            throw new AccessDeniedException("No role that grants batch access");
        }
        return assignments.batchIds(caller.academyId(), caller.accountId(), scoped);
    }

    @Transactional
    public BatchView create(CurrentAccount caller, UUID courseId, String code, String name, LocalDate startsOn, LocalDate endsOn) {
        caller.requireAcademyManager();
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
        caller.requireAcademyManager();
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
