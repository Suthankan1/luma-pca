package com.physicscube.luma.batches;

import com.physicscube.luma.courses.CourseService;
import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batches of the caller's academy. Only academy owners and administrators may use
 * it until scoped staff assignments exist: an instructor's role alone grants no
 * batch. A batch or course in another academy is reported as not found.
 */
@Service
public class BatchService {
    private final BatchRepository batches;
    private final CourseService courses;

    BatchService(BatchRepository batches, CourseService courses) {
        this.batches = batches;
        this.courses = courses;
    }

    /** @param courseId {@code null} for every batch of the academy */
    @Transactional(readOnly = true)
    public List<BatchView> list(CurrentAccount caller, UUID courseId) {
        caller.requireAcademyManager();
        List<Batch> found;
        if (courseId == null) {
            found = batches.findByAcademyIdOrderByStartsOnDescCodeAsc(caller.academyId());
        } else {
            courses.requireInAcademy(caller.academyId(), courseId);
            found = batches.findByAcademyIdAndCourseIdOrderByStartsOnDescCodeAsc(caller.academyId(), courseId);
        }
        return found.stream().map(BatchView::of).toList();
    }

    @Transactional(readOnly = true)
    public BatchView get(CurrentAccount caller, UUID batchId) {
        caller.requireAcademyManager();
        return BatchView.of(load(caller.academyId(), batchId));
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
            return BatchView.of(batches.saveAndFlush(batch));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
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
