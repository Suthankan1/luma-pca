package com.physicscube.luma.batches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.courses.CourseService;
import com.physicscube.luma.enrollments.EnrollmentService;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/** Calls the services directly to prove batch scope without the route rules. */
class BatchScopeServiceIntegrationTest extends StaffApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2027, 1, 10);

    @Autowired
    CourseService courses;
    @Autowired
    BatchService batches;
    @Autowired
    StaffAssignmentService assignments;
    @Autowired
    EnrollmentService enrollments;

    @Test
    void scopeIsEnforcedInsideTheServices() {
        UUID academyId = academy("Physics Cube Test");
        UUID adminId = accounts.register(academyId, "admin@example.invalid", Set.of(Role.ACADEMY_ADMIN)).id();
        CurrentAccount admin = new CurrentAccount(adminId, academyId, Set.of("ACADEMY_ADMIN"));
        UUID course = courses.create(admin, "PHY", "Physics").id();
        UUID batchA = batches.create(admin, course, "A", "A", DAY, null).id();
        UUID batchB = batches.create(admin, course, "B", "B", DAY, null).id();
        UUID instructorId = accounts.register(academyId, "teacher@example.invalid", Set.of(Role.INSTRUCTOR)).id();
        assignments.assign(admin, batchA, "teacher@example.invalid", "INSTRUCTOR");
        CurrentAccount instructor = new CurrentAccount(instructorId, academyId, Set.of("INSTRUCTOR"));

        assertEquals(List.of(batchA), batches.list(instructor, null).stream().map(BatchView::id).toList());
        assertEquals(batchA, batches.get(instructor, batchA).id());
        assertEquals(List.of(), enrollments.roster(instructor, batchA));
        assertThrows(ResourceNotFoundException.class, () -> batches.get(instructor, batchB));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.roster(instructor, batchB));
        assertThrows(AccessDeniedException.class, () -> batches.create(instructor, course, "C", "C", DAY, null));
        assertThrows(AccessDeniedException.class, () -> assignments.assign(instructor, batchB, "teacher@example.invalid", "INSTRUCTOR"));
        assertThrows(AccessDeniedException.class, () -> assignments.forBatch(instructor, batchA));

        CurrentAccount sameAccountWithoutRole = new CurrentAccount(instructorId, academyId, Set.of("ANALYST"));
        assertThrows(AccessDeniedException.class, () -> batches.get(sameAccountWithoutRole, batchA));
        CurrentAccount sameAccountWrongRole = new CurrentAccount(instructorId, academyId, Set.of("ATTENDANCE_OFFICER"));
        assertThrows(ResourceNotFoundException.class, () -> batches.get(sameAccountWrongRole, batchA),
                "an instructor assignment does not grant attendance-officer access");
        CurrentAccount otherAcademy = new CurrentAccount(instructorId, academy("Other Academy"), Set.of("INSTRUCTOR"));
        assertEquals(List.of(), batches.list(otherAcademy, null));
        assertThrows(ResourceNotFoundException.class, () -> batches.get(otherAcademy, batchA));
    }
}
