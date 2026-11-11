package com.physicscube.luma.batches;

import com.physicscube.luma.identity.AccountService;
import com.physicscube.luma.identity.AccountSummary;
import com.physicscube.luma.identity.domain.AccountStatus;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.InvalidRequestException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns instructors and attendance officers to batches. Only academy owners and
 * administrators manage assignments; assigned staff can list their own.
 */
@Service
public class StaffAssignmentService {
    private final StaffAssignmentRepository assignments;
    private final BatchService batches;
    private final AccountService accounts;

    StaffAssignmentService(StaffAssignmentRepository assignments, BatchService batches, AccountService accounts) {
        this.assignments = assignments;
        this.batches = batches;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> forBatch(CurrentAccount caller, UUID batchId) {
        caller.requireAcademyManager();
        BatchView batch = batches.requireInAcademy(caller.academyId(), batchId);
        List<StaffAssignment> found = assignments.findByAcademyIdAndBatchIdOrderByRoleAscCreatedAtAsc(caller.academyId(), batchId);
        Map<UUID, AccountSummary> people = accounts.findByIds(found.stream().map(StaffAssignment::getAccountId).toList());
        return found.stream().map(a -> view(a, batch, people.get(a.getAccountId()))).toList();
    }

    /** The caller's own assignments in the roles they currently hold. */
    @Transactional(readOnly = true)
    public List<AssignmentView> mine(CurrentAccount caller) {
        if (caller.batchScopedRoles().isEmpty()) {
            throw new AccessDeniedException("Instructor or attendance officer role required");
        }
        List<StaffAssignment> found = assignments.findByAcademyIdAndAccountIdAndRoleIn(caller.academyId(), caller.accountId(),
                caller.batchScopedRoles());
        Map<UUID, BatchView> batchViews = batches.viewsInAcademy(caller.academyId(),
                found.stream().map(StaffAssignment::getBatchId).toList());
        AccountSummary self = accounts.findById(caller.accountId()).orElse(null);
        return found.stream().map(a -> view(a, batchViews.get(a.getBatchId()), self))
                .sorted(Comparator.comparing(AssignmentView::batchCode).thenComparing(AssignmentView::role)).toList();
    }

    /**
     * Assigns the staff member with this email. The account must be in the caller's
     * academy, not disabled, and already hold the role; an email from another academy
     * is reported exactly like an unknown one.
     */
    @Transactional
    public AssignmentView assign(CurrentAccount caller, UUID batchId, String email, String role) {
        caller.requireAcademyManager();
        if (!CurrentAccount.BATCH_SCOPED_ROLES.contains(role)) {
            throw new InvalidRequestException("role must be INSTRUCTOR or ATTENDANCE_OFFICER");
        }
        BatchView batch = batches.requireInAcademy(caller.academyId(), batchId);
        AccountSummary account;
        try {
            account = accounts.findByEmail(email).filter(a -> a.academyId().equals(caller.academyId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Staff account"));
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Staff account");
        }
        if (account.status() == AccountStatus.DISABLED) {
            throw new ConflictException("A disabled account cannot be assigned");
        }
        if (!account.roles().contains(Role.valueOf(role))) {
            throw new ConflictException("The account does not hold the " + role + " role");
        }
        if (assignments.existsByBatchIdAndAccountIdAndRole(batchId, account.id(), role)) {
            throw alreadyAssigned();
        }
        try {
            StaffAssignment saved = assignments.saveAndFlush(
                    new StaffAssignment(caller.academyId(), account.id(), batchId, role, caller.accountId()));
            return view(saved, batch, account);
        } catch (DataIntegrityViolationException e) {
            throw String.valueOf(e.getMessage()).contains("uq_staff_assignments") ? alreadyAssigned() : e;
        }
    }

    /** Revokes the access immediately: the next request by that staff member no longer sees the batch. */
    @Transactional
    public void unassign(CurrentAccount caller, UUID assignmentId) {
        caller.requireAcademyManager();
        assignments.delete(assignments.findByIdAndAcademyId(assignmentId, caller.academyId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignment")));
    }

    private static AssignmentView view(StaffAssignment a, BatchView batch, AccountSummary account) {
        return new AssignmentView(a.getId(), a.getBatchId(), batch.courseId(), batch.code(), batch.name(), a.getAccountId(),
                account == null ? null : account.email(), a.getRole(), a.getCreatedAt());
    }

    private static ConflictException alreadyAssigned() {
        return new ConflictException("The account is already assigned to this batch in this role");
    }
}
