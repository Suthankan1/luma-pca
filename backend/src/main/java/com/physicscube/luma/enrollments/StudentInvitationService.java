package com.physicscube.luma.enrollments;

import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.identity.InvitationEmailQueue;
import com.physicscube.luma.identity.InvitationEmailQueue.QueueResult;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.students.StudentService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emails activation invitations to many students: every active student of the academy, or
 * the active students currently enrolled in one batch (not withdrawn as of today at the
 * academy). Sending happens in the background; see {@link InvitationEmailQueue}.
 */
@Service
public class StudentInvitationService {
    private final EnrollmentRepository enrollments;
    private final BatchService batches;
    private final StudentService students;
    private final InvitationEmailQueue queue;

    StudentInvitationService(EnrollmentRepository enrollments, BatchService batches, StudentService students, InvitationEmailQueue queue) {
        this.enrollments = enrollments;
        this.batches = batches;
        this.students = students;
        this.queue = queue;
    }

    /** A student whose invitation email failed, for staff to check the address or send a link another way. */
    public record FailedInvitation(UUID studentId, String studentNumber, String fullName, String email, String reason,
                                   java.time.Instant failedAt, int attempts) {
    }

    @Transactional(readOnly = true)
    public List<FailedInvitation> failures(CurrentAccount caller) {
        List<InvitationEmailQueue.Failure> failed = queue.failures(caller);
        java.util.Map<UUID, com.physicscube.luma.students.StudentView> found = students.byAccountIds(caller.academyId(),
                failed.stream().map(InvitationEmailQueue.Failure::accountId).toList());
        return failed.stream().filter(f -> found.containsKey(f.accountId())).map(f -> {
            com.physicscube.luma.students.StudentView s = found.get(f.accountId());
            return new FailedInvitation(s.id(), s.studentNumber(), s.fullName(), s.email(), f.reason(), f.failedAt(), f.attempts());
        }).toList();
    }

    @Transactional
    public QueueResult invite(CurrentAccount caller, UUID batchId, boolean resend, boolean dryRun) {
        caller.requireAcademyManager();
        List<UUID> accountIds;
        if (batchId == null) {
            accountIds = students.activeAccountIds(caller.academyId(), null);
        } else {
            batches.requireInAcademy(caller.academyId(), batchId);
            LocalDate today = LocalDate.now(ZoneId.of(ClassSessionService.DEFAULT_TIME_ZONE));
            List<UUID> current = enrollments.findByAcademyIdAndBatchId(caller.academyId(), batchId).stream()
                    .filter(e -> e.getWithdrawnOn() == null || e.getWithdrawnOn().isAfter(today))
                    .map(Enrollment::getStudentId).toList();
            accountIds = students.activeAccountIds(caller.academyId(), current);
        }
        return queue.queue(caller, accountIds, resend, dryRun, batchId == null ? "ACADEMY" : "BATCH",
                batchId == null ? caller.academyId() : batchId);
    }
}
