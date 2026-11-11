package com.physicscube.luma.batches;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every query is bound to an academy; there is deliberately no lookup by id alone. */
interface BatchRepository extends JpaRepository<Batch, UUID> {
    Optional<Batch> findByIdAndAcademyId(UUID id, UUID academyId);

    List<Batch> findByAcademyIdOrderByStartsOnDescCodeAsc(UUID academyId);

    List<Batch> findByAcademyIdAndCourseIdOrderByStartsOnDescCodeAsc(UUID academyId, UUID courseId);

    boolean existsByCourseIdAndCode(UUID courseId, String code);
}
