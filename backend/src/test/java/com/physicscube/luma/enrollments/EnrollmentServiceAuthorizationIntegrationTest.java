package com.physicscube.luma.enrollments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.courses.CourseService;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import com.physicscube.luma.students.StudentService;
import com.physicscube.luma.students.StudentView;
import com.physicscube.luma.support.StaffApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/** Calls the services directly to prove role, academy and ownership checks without the route rules. */
class EnrollmentServiceAuthorizationIntegrationTest extends StaffApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2027, 1, 10);

    @Autowired
    CourseService courses;
    @Autowired
    BatchService batches;
    @Autowired
    StudentService students;
    @Autowired
    EnrollmentService enrollments;

    private static CurrentAccount caller(UUID academyId, UUID accountId, String... roles) {
        return new CurrentAccount(accountId, academyId, Set.of(roles));
    }

    @Test
    void servicesEnforceRolesAcademiesAndOwnership() {
        UUID academyId = academy("Physics Cube Test");
        CurrentAccount admin = caller(academyId, UUID.randomUUID(), "ACADEMY_ADMIN");
        UUID batch = batches.create(admin, courses.create(admin, "PHY", "Physics").id(), "A", "A", DAY, null).id();
        StudentView me = students.create(admin, "PCA-1", "Me", "me@example.invalid");
        StudentView classmate = students.create(admin, "PCA-2", "Classmate", "classmate@example.invalid");
        UUID enrollment = enrollments.enroll(admin, batch, me.id(), DAY).id();
        enrollments.enroll(admin, batch, classmate.id(), DAY);

        for (CurrentAccount denied : List.of(caller(academyId, UUID.randomUUID(), "INSTRUCTOR"),
                caller(academyId, me.accountId(), "STUDENT"), caller(academyId, UUID.randomUUID()))) {
            assertThrows(AccessDeniedException.class, () -> students.list(denied, null, null, 50));
            assertThrows(AccessDeniedException.class, () -> students.get(denied, me.id()));
            assertThrows(AccessDeniedException.class, () -> students.create(denied, "X", "X", "x@example.invalid"));
            assertThrows(AccessDeniedException.class, () -> students.update(denied, me.id(), "X", true, 0));
            if (denied.batchScopedRoles().isEmpty()) {
                assertThrows(AccessDeniedException.class, () -> enrollments.roster(denied, batch));
            } else {
                assertThrows(ResourceNotFoundException.class, () -> enrollments.roster(denied, batch),
                        "an unassigned instructor gets not found");
            }
            assertThrows(AccessDeniedException.class, () -> enrollments.forStudent(denied, me.id()));
            assertThrows(AccessDeniedException.class, () -> enrollments.enroll(denied, batch, me.id(), DAY));
            assertThrows(AccessDeniedException.class, () -> enrollments.update(denied, enrollment, DAY, null, 0));
        }

        CurrentAccount self = caller(academyId, me.accountId(), "STUDENT");
        assertEquals(me.id(), students.mine(self).id());
        assertEquals(List.of(me.id()), enrollments.mine(self).stream().map(EnrollmentView::studentId).toList());
        CurrentAccount sameAccountOtherAcademy = caller(academy("Other Academy"), me.accountId(), "STUDENT");
        assertThrows(ResourceNotFoundException.class, () -> students.mine(sameAccountOtherAcademy),
                "ownership is matched on account and academy together");
        assertThrows(AccessDeniedException.class, () -> enrollments.mine(caller(academyId, me.accountId(), "INSTRUCTOR")));

        CurrentAccount outsider = caller(academy("Third Academy"), UUID.randomUUID(), "ACADEMY_OWNER");
        assertThrows(ResourceNotFoundException.class, () -> students.get(outsider, me.id()));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.roster(outsider, batch));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.forStudent(outsider, me.id()));
        assertThrows(ResourceNotFoundException.class, () -> enrollments.update(outsider, enrollment, DAY, null, 0));
        assertEquals(List.of(), students.list(outsider, null, null, 50).items());
    }
}
