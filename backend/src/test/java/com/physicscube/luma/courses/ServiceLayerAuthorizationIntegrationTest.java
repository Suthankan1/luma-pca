package com.physicscube.luma.courses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.physicscube.luma.batches.BatchService;
import com.physicscube.luma.batches.BatchView;
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

/**
 * Calls the services directly, bypassing the HTTP route rules, to prove that role
 * and academy checks do not depend on the security filter chain being configured
 * correctly.
 */
class ServiceLayerAuthorizationIntegrationTest extends StaffApiIntegrationTest {
    private static final LocalDate START = LocalDate.of(2027, 1, 10);

    @Autowired
    CourseService courses;
    @Autowired
    BatchService batches;

    private static CurrentAccount caller(UUID academyId, String... roles) {
        return new CurrentAccount(UUID.randomUUID(), academyId, Set.of(roles));
    }

    @Test
    void servicesRejectCallersWithoutTheStaffRole() {
        UUID academyId = academy("Physics Cube Test");
        CurrentAccount admin = caller(academyId, "STAFF");
        CourseView course = courses.create(admin, "PHY", "Physics");
        BatchView batch = batches.create(admin, course.id(), "MON", "Monday", START, null);

        // A former role name left in a token or test must grant nothing; only STAFF opens staff services.
        for (CurrentAccount denied : List.of(caller(academyId, "STUDENT"), caller(academyId),
                caller(academyId, "ACADEMY_OWNER"), caller(academyId, "INSTRUCTOR"), caller(academyId, "ANALYST"),
                caller(academyId, "staff"))) {
            assertThrows(AccessDeniedException.class, () -> courses.list(denied, null));
            assertThrows(AccessDeniedException.class, () -> courses.get(denied, course.id()));
            assertThrows(AccessDeniedException.class, () -> courses.create(denied, "NEW", "New"));
            assertThrows(AccessDeniedException.class, () -> courses.update(denied, course.id(), "PHY", "Changed", false, 0));
            assertThrows(AccessDeniedException.class, () -> batches.list(denied, null));
            assertThrows(AccessDeniedException.class, () -> batches.get(denied, batch.id()));
            assertThrows(AccessDeniedException.class, () -> batches.create(denied, course.id(), "NEW", "New", START, null));
            assertThrows(AccessDeniedException.class,
                    () -> batches.update(denied, batch.id(), "MON", "Changed", START, null, false, 0));
        }
        assertEquals("Physics", courses.get(admin, course.id()).title());
        assertEquals("Monday", batches.get(admin, batch.id()).name());
        assertEquals(1, courses.list(caller(academyId, "STAFF"), null).size());
    }

    @Test
    void servicesNeverCrossAcademiesEvenForStaff() {
        UUID ours = academy("Physics Cube Test");
        UUID theirs = academy("Other Academy");
        CurrentAccount admin = caller(ours, "STAFF");
        CurrentAccount outsider = caller(theirs, "STAFF");
        CourseView course = courses.create(admin, "PHY", "Physics");
        BatchView batch = batches.create(admin, course.id(), "MON", "Monday", START, null);

        assertEquals(List.of(), courses.list(outsider, null));
        assertEquals(List.of(), batches.list(outsider, null));
        assertThrows(ResourceNotFoundException.class, () -> courses.get(outsider, course.id()));
        assertThrows(ResourceNotFoundException.class, () -> courses.update(outsider, course.id(), "X", "X", false, 0));
        assertThrows(ResourceNotFoundException.class, () -> batches.get(outsider, batch.id()));
        assertThrows(ResourceNotFoundException.class, () -> batches.list(outsider, course.id()));
        assertThrows(ResourceNotFoundException.class, () -> batches.create(outsider, course.id(), "X", "X", START, null));
        assertThrows(ResourceNotFoundException.class,
                () -> batches.update(outsider, batch.id(), "X", "X", START, null, false, 0));
        assertThrows(ResourceNotFoundException.class, () -> courses.requireInAcademy(theirs, course.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM courses WHERE academy_id = ?", Integer.class, theirs));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM batches WHERE academy_id = ?", Integer.class, theirs));
    }

    /** Guards against platform clocks finer than the database: a write must return exactly what a read returns. */
    @Test
    void valuesReturnedByWritesEqualWhatIsReadBack() {
        CurrentAccount admin = caller(academy("Physics Cube Test"), "STAFF");

        CourseView created = courses.create(admin, "PHY", "Physics");
        assertEquals(created, courses.get(admin, created.id()));
        assertEquals(0, created.createdAt().getNano() % 1_000);
        CourseView updated = courses.update(admin, created.id(), "PHY", "Physics 2", true, 0);
        assertEquals(updated, courses.get(admin, created.id()));
        assertEquals(0, updated.updatedAt().getNano() % 1_000);

        BatchView batch = batches.create(admin, created.id(), "MON", "Monday", START, null);
        assertEquals(batch, batches.get(admin, batch.id()));
        BatchView changed = batches.update(admin, batch.id(), "MON", "Monday 2", START, null, true, 0);
        assertEquals(changed, batches.get(admin, batch.id()));
        assertEquals(0, changed.updatedAt().getNano() % 1_000);
    }
}
