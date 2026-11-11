package com.physicscube.luma.enrollments;

import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.batches.BatchView;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentView;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch enrollments. Managing and reading rosters is limited to academy owners and
 * administrators until scoped staff assignments exist. A student reads only their
 * own enrollments, never a roster. Records of another academy are reported as not found.
 */
@Service
public class EnrollmentService {
    private final EnrollmentRepository enrollments;
    private final BatchService batches;
    private final StudentService students;

    EnrollmentService(EnrollmentRepository enrollments, BatchService batches, StudentService students) {
        this.enrollments = enrollments;
        this.batches = batches;
        this.students = students;
    }

    /** Everyone ever enrolled in the batch, withdrawn included, by student number. */
    @Transactional(readOnly = true)
    public List<EnrollmentView> roster(CurrentAccount caller, UUID batchId) {
        caller.requireAcademyManager();
        batches.requireInAcademy(caller.academyId(), batchId);
        return views(caller.academyId(), enrollments.findByAcademyIdAndBatchId(caller.academyId(), batchId));
    }

    @Transactional(readOnly = true)
    public List<EnrollmentView> forStudent(CurrentAccount caller, UUID studentId) {
        caller.requireAcademyManager();
        students.requireInAcademy(caller.academyId(), studentId);
        return views(caller.academyId(), enrollments.findByAcademyIdAndStudentId(caller.academyId(), studentId));
    }

    /** The calling student's own enrollments. */
    @Transactional(readOnly = true)
    public List<EnrollmentView> mine(CurrentAccount caller) {
        caller.requireRole(Role.STUDENT.name());
        UUID studentId = students.idForAccount(caller.academyId(), caller.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Student record"));
        return views(caller.academyId(), enrollments.findByAcademyIdAndStudentId(caller.academyId(), studentId));
    }

    @Transactional
    public EnrollmentView enroll(CurrentAccount caller, UUID batchId, UUID studentId, LocalDate enrolledOn) {
        caller.requireAcademyManager();
        BatchView batch = batches.requireInAcademy(caller.academyId(), batchId);
        StudentView student = students.requireInAcademy(caller.academyId(), studentId);
        if (!batch.active()) {
            throw new ConflictException("Students cannot be enrolled in an inactive batch");
        }
        if (!student.active()) {
            throw new ConflictException("An inactive student cannot be enrolled");
        }
        if (enrollments.existsByBatchIdAndStudentId(batchId, studentId)) {
            throw alreadyEnrolled();
        }
        try {
            Enrollment saved = enrollments.saveAndFlush(new Enrollment(caller.academyId(), batchId, studentId, enrolledOn));
            return view(saved, batch, student);
        } catch (DataIntegrityViolationException e) {
            throw String.valueOf(e.getMessage()).contains("uq_enrollments_batch_student") ? alreadyEnrolled() : e;
        }
    }

    /** Sets the dates; a withdrawal date withdraws, clearing it re-enrols. */
    @Transactional
    public EnrollmentView update(CurrentAccount caller, UUID enrollmentId, LocalDate enrolledOn, LocalDate withdrawnOn,
                                 long expectedVersion) {
        caller.requireAcademyManager();
        if (withdrawnOn != null && withdrawnOn.isBefore(enrolledOn)) {
            throw new InvalidRequestException("withdrawnOn must not be before enrolledOn");
        }
        Enrollment enrollment = enrollments.findByIdAndAcademyId(enrollmentId, caller.academyId())
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment"));
        if (enrollment.getVersion() != expectedVersion) {
            throw new ConflictException("The record was changed by someone else; reload and retry");
        }
        enrollment.update(enrolledOn, withdrawnOn);
        Enrollment saved = enrollments.saveAndFlush(enrollment);
        return view(saved, batches.requireInAcademy(caller.academyId(), saved.getBatchId()),
                students.requireInAcademy(caller.academyId(), saved.getStudentId()));
    }

    private List<EnrollmentView> views(UUID academyId, List<Enrollment> found) {
        Map<UUID, BatchView> batchViews = batches.viewsInAcademy(academyId, found.stream().map(Enrollment::getBatchId).toList());
        Map<UUID, StudentView> studentViews = students.viewsInAcademy(academyId,
                found.stream().map(Enrollment::getStudentId).toList());
        return found.stream().map(e -> view(e, batchViews.get(e.getBatchId()), studentViews.get(e.getStudentId())))
                .sorted(Comparator.comparing(EnrollmentView::studentNumber).thenComparing(EnrollmentView::batchCode)
                        .thenComparing(EnrollmentView::courseId))
                .toList();
    }

    private static EnrollmentView view(Enrollment e, BatchView batch, StudentView student) {
        return new EnrollmentView(e.getId(), e.getBatchId(), batch.courseId(), batch.code(), batch.name(),
                e.getStudentId(), student.studentNumber(), student.fullName(), e.getEnrolledOn(), e.getWithdrawnOn(),
                e.getWithdrawnOn() == null ? "ENROLLED" : "WITHDRAWN", e.getVersion());
    }

    private static ConflictException alreadyEnrolled() {
        return new ConflictException("The student is already enrolled in this batch");
    }
}
