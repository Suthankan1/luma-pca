package com.physicscube.luma.courses;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every query is bound to an academy; there is deliberately no lookup by id alone. */
interface CourseRepository extends JpaRepository<Course, UUID> {
    Optional<Course> findByIdAndAcademyId(UUID id, UUID academyId);

    List<Course> findByAcademyIdOrderByCode(UUID academyId);

    List<Course> findByAcademyIdAndActiveOrderByCode(UUID academyId, boolean active);

    boolean existsByAcademyIdAndCode(UUID academyId, String code);
}
