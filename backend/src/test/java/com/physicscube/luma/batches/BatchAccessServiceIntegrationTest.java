package com.physicscube.luma.batches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.courses.CourseService;
import com.physicscube.luma.enrollments.EnrollmentService;
import com.physicscube.luma.identity.domain.Role;
import com.physicscube.luma.sessions.ClassSessionService;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/**
 * Calls the services directly, without the route rules: with two roles (owner decision 2026-11-20) every staff member
 * reads and manages every batch of their own academy, a student reaches no staff service, and another academy's batch
 * is not found.
 */
class BatchAccessServiceIntegrationTest extends StaffApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2027, 1, 10);

    @Autowired
    CourseService courses;
    @Autowired
    BatchService batches;
    @Autowired
    EnrollmentService enrollments;
    @Autowired
    ClassSessionService sessions;

    @Test
    void everyStaffMemberSeesEveryBatchOfTheirAcademyOnly() {
        UUID academyId = academy("Physics Cube Test");
        UUID firstId = accounts.register(academyId, "first@example.invalid", Set.of(Role.STAFF)).id();
        CurrentAccount first = new CurrentAccount(firstId, academyId, Set.of("STAFF"));
        UUID course = courses.create(first, "PHY", "Physics").id();
        UUID batchA = batches.create(first, course, "A", "A", DAY, null).id();
        UUID batchB = batches.create(first, course, "B", "B", DAY, null).id();

        UUID secondId = accounts.register(academyId, "second@example.invalid", Set.of(Role.STAFF)).id();
        CurrentAccount second = new CurrentAccount(secondId, academyId, Set.of("STAFF"));
        assertEquals(Set.of(batchA, batchB), Set.copyOf(batches.list(second, null).stream().map(BatchView::id).toList()));
        assertEquals(batchB, batches.get(second, batchB).id());
        assertEquals(List.of(), enrollments.roster(second, batchB));
        UUID batchC = batches.create(second, course, "C", "C", DAY, null).id();
        assertEquals(batchC, batches.get(first, batchC).id(), "a batch one staff member adds is everyone's");

        CurrentAccount otherAcademy = new CurrentAccount(secondId, academy("Other Academy"), Set.of("STAFF"));
        assertEquals(List.of(), batches.list(otherAcademy, null));
        assertThrows(ResourceNotFoundException.class, () -> batches.get(otherAcademy, batchA));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.roster(otherAcademy, batchA));
    }

    @Test
    void aStudentReachesNoStaffService() {
        UUID academyId = academy("Physics Cube Test");
        CurrentAccount staff = new CurrentAccount(accounts.register(academyId, "staff@example.invalid", Set.of(Role.STAFF)).id(),
                academyId, Set.of("STAFF"));
        UUID course = courses.create(staff, "PHY", "Physics").id();
        UUID batch = batches.create(staff, course, "A", "A", DAY, null).id();
        CurrentAccount student = new CurrentAccount(accounts.register(academyId, "student@example.invalid", Set.of(Role.STUDENT)).id(),
                academyId, Set.of("STUDENT"));
        Instant from = Instant.parse("2027-01-01T00:00:00Z");

        assertThrows(AccessDeniedException.class, () -> batches.list(student, null));
        assertThrows(AccessDeniedException.class, () -> batches.get(student, batch));
        assertThrows(AccessDeniedException.class, () -> batches.create(student, course, "B", "B", DAY, null));
        assertThrows(AccessDeniedException.class, () -> enrollments.roster(student, batch));
        assertThrows(AccessDeniedException.class, () -> sessions.list(student, from, from.plusSeconds(86_400), null));
        assertThrows(AccessDeniedException.class, () -> sessions.get(student, UUID.randomUUID()));
        assertThrows(AccessDeniedException.class, () -> sessions.expectedStudents(student, UUID.randomUUID()));
        CurrentAccount noRole = new CurrentAccount(staff.accountId(), academyId, Set.of());
        assertThrows(AccessDeniedException.class, () -> batches.list(noRole, null));
    }
}
