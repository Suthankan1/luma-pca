package com.physicscube.luma.courses;

import java.time.Instant;
import java.util.UUID;

public record CourseView(UUID id, String code, String title, boolean active, long version,
                         Instant createdAt, Instant updatedAt) {
    static CourseView of(Course course) {
        return new CourseView(course.getId(), course.getCode(), course.getTitle(), course.isActive(),
                course.getVersion(), course.getCreatedAt(), course.getUpdatedAt());
    }
}
