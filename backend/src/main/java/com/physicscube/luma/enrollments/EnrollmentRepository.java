package com.physicscube.luma.enrollments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every query is bound to an academy; there is deliberately no lookup by id alone. */
interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {
    Optional<Enrollment> findByIdAndAcademyId(UUID id, UUID academyId);

    List<Enrollment> findByAcademyIdAndBatchId(UUID academyId, UUID batchId);

    List<Enrollment> findByAcademyIdAndStudentId(UUID academyId, UUID studentId);

    boolean existsByBatchIdAndStudentId(UUID batchId, UUID studentId);
}
