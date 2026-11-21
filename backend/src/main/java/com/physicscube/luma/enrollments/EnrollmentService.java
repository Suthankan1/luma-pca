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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch enrollments. Staff manage them and read every roster of their academy. A student
 * reads only their own enrollments, never a roster. Records of another academy are reported as not found.
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
        batches.requireReadable(caller, batchId);
        return views(caller.academyId(), enrollments.findByAcademyIdAndBatchId(caller.academyId(), batchId));
    }

    @Transactional(readOnly = true)
    public List<EnrollmentView> forStudent(CurrentAccount caller, UUID studentId) {
        caller.requireStaff();
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
        caller.requireStaff();
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

    /** Checks the batch can take enrolments: in the caller's academy (else not found) and active. */
    @Transactional(readOnly = true)
    public BatchView requireOpenBatch(CurrentAccount caller, UUID batchId) {
        caller.requireStaff();
        BatchView batch = batches.requireInAcademy(caller.academyId(), batchId);
        if (!batch.active()) {
            throw new ConflictException("Students cannot be enrolled in an inactive batch");
        }
        return batch;
    }

    /** Enrols students just created in this transaction (so active and not yet enrolled) in one batch insert. */
    @Transactional
    public int enrollNew(CurrentAccount caller, UUID batchId, Collection<UUID> studentIds, LocalDate enrolledOn) {
        requireOpenBatch(caller, batchId);
        enrollments.saveAllAndFlush(studentIds.stream()
                .map(studentId -> new Enrollment(caller.academyId(), batchId, studentId, enrolledOn)).toList());
        return studentIds.size();
    }

    /** Sets the dates; a withdrawal date withdraws, clearing it re-enrols. */
    @Transactional
    public EnrollmentView update(CurrentAccount caller, UUID enrollmentId, LocalDate enrolledOn, LocalDate withdrawnOn,
                                 long expectedVersion) {
        caller.requireStaff();
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

    /**
     * For other modules: each student enrolled on {@code day} in any of the batches,
     * listed once with every one of those batches they belong to. A withdrawal date
     * is the first day the student is no longer enrolled. No role check; the caller
     * decides which batches the requester may see.
     */
    @Transactional(readOnly = true)
    public List<ExpectedStudent> expectedOn(UUID academyId, Collection<UUID> batchIds, LocalDate day) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UUID>> batchesByStudent = new TreeMap<>();
        for (Enrollment e : enrollments.overlapping(academyId, batchIds, day, day)) {
            batchesByStudent.computeIfAbsent(e.getStudentId(), id -> new ArrayList<>()).add(e.getBatchId());
        }
        Map<UUID, StudentView> people = students.viewsInAcademy(academyId, batchesByStudent.keySet());
        return expectedStudents(batchesByStudent, people);
    }

    private static List<ExpectedStudent> expectedStudents(Map<UUID, List<UUID>> batchesByStudent, Map<UUID, StudentView> people) {
        return batchesByStudent.entrySet().stream()
                .map(entry -> new ExpectedStudent(entry.getKey(), people.get(entry.getKey()).studentNumber(),
                        people.get(entry.getKey()).fullName(), entry.getValue().stream().sorted().toList()))
                .sorted(Comparator.comparing(ExpectedStudent::studentNumber))
                .toList();
    }

    public record RosterRequest(UUID sessionId, LocalDate day, Set<UUID> batchIds) {
    }

    /** Expected student IDs per session, without names. The caller supplies only batches the requester may see. */
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> expectedStudentIds(UUID academyId, List<RosterRequest> requests) {
        Map<UUID, List<Enrollment>> rosters = expectedEnrollments(academyId, requests);
        return rosters.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry ->
                entry.getValue().stream().map(Enrollment::getStudentId).collect(Collectors.toSet())));
    }

    /** Named rosters per authorized session, using each session's local date and selected batches. */
    @Transactional(readOnly = true)
    public Map<UUID, List<ExpectedStudent>> expectedStudentsBySession(UUID academyId, List<RosterRequest> requests) {
        Map<UUID, List<Enrollment>> rosters = expectedEnrollments(academyId, requests);
        Set<UUID> ids = rosters.values().stream().flatMap(List::stream).map(Enrollment::getStudentId).collect(Collectors.toSet());
        Map<UUID, StudentView> people = students.viewsInAcademy(academyId, ids);
        Map<UUID, List<ExpectedStudent>> result = new java.util.HashMap<>();
        rosters.forEach((sessionId, roster) -> {
            Map<UUID, List<UUID>> batchesByStudent = new TreeMap<>();
            for (Enrollment e : roster) {
                batchesByStudent.computeIfAbsent(e.getStudentId(), id -> new ArrayList<>()).add(e.getBatchId());
            }
            result.put(sessionId, expectedStudents(batchesByStudent, people));
        });
        return result;
    }

    private Map<UUID, List<Enrollment>> expectedEnrollments(UUID academyId, List<RosterRequest> requests) {
        Set<UUID> batchIds = requests.stream().flatMap(request -> request.batchIds().stream()).collect(Collectors.toSet());
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        List<LocalDate> days = requests.stream().map(RosterRequest::day).sorted().toList();
        Map<UUID, List<Enrollment>> byBatch = enrollments.overlapping(academyId, batchIds, days.getFirst(), days.getLast()).stream()
                .collect(Collectors.groupingBy(Enrollment::getBatchId));
        return requests.stream().collect(Collectors.toMap(RosterRequest::sessionId, request -> request.batchIds().stream()
                .flatMap(batchId -> byBatch.getOrDefault(batchId, List.of()).stream())
                .filter(enrollment -> !enrollment.getEnrolledOn().isAfter(request.day())
                        && (enrollment.getWithdrawnOn() == null || enrollment.getWithdrawnOn().isAfter(request.day())))
                .toList()));
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
