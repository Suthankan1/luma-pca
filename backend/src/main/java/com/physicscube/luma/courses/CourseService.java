package com.physicscube.luma.courses;

import com.physicscube.luma.shared.api.Codes;
import com.physicscube.luma.shared.api.ConflictException;
import com.physicscube.luma.shared.api.ResourceNotFoundException;
import com.physicscube.luma.shared.security.CurrentAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Courses of the caller's academy. Only academy owners and administrators may use
 * it until scoped staff assignments exist. A course in another academy is reported
 * exactly like one that does not exist.
 */
@Service
public class CourseService {
    private final CourseRepository courses;

    CourseService(CourseRepository courses) {
        this.courses = courses;
    }

    /** @param active {@code null} for all courses */
    @Transactional(readOnly = true)
    public List<CourseView> list(CurrentAccount caller, Boolean active) {
        caller.requireAcademyManager();
        List<Course> found = active == null ? courses.findByAcademyIdOrderByCode(caller.academyId())
                : courses.findByAcademyIdAndActiveOrderByCode(caller.academyId(), active);
        return found.stream().map(CourseView::of).toList();
    }

    @Transactional(readOnly = true)
    public CourseView get(CurrentAccount caller, UUID courseId) {
        caller.requireAcademyManager();
        return CourseView.of(load(caller.academyId(), courseId));
    }

    @Transactional
    public CourseView create(CurrentAccount caller, String code, String title) {
        caller.requireAcademyManager();
        String normalized = Codes.normalize(code);
        if (courses.existsByAcademyIdAndCode(caller.academyId(), normalized)) {
            throw duplicateCode();
        }
        try {
            return CourseView.of(courses.saveAndFlush(new Course(caller.academyId(), normalized, title.strip())));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /** @param expectedVersion the version the caller last read; a mismatch means someone else changed the course */
    @Transactional
    public CourseView update(CurrentAccount caller, UUID courseId, String code, String title, boolean active,
                             long expectedVersion) {
        caller.requireAcademyManager();
        Course course = load(caller.academyId(), courseId);
        if (course.getVersion() != expectedVersion) {
            throw new ConflictException("The record was changed by someone else; reload and retry");
        }
        course.update(Codes.normalize(code), title.strip(), active);
        try {
            return CourseView.of(courses.saveAndFlush(course));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /**
     * For other modules: confirms the course belongs to the academy and returns
     * whether it is active. The caller remains responsible for its own role checks.
     */
    @Transactional(readOnly = true)
    public boolean requireInAcademy(UUID academyId, UUID courseId) {
        return load(academyId, courseId).isActive();
    }

    private Course load(UUID academyId, UUID courseId) {
        return courses.findByIdAndAcademyId(courseId, academyId).orElseThrow(() -> new ResourceNotFoundException("Course"));
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        return String.valueOf(e.getMessage()).contains("uq_courses_academy_code") ? duplicateCode() : e;
    }

    private static ConflictException duplicateCode() {
        return new ConflictException("A course with this code already exists");
    }
}
