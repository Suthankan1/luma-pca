package com.physicscube.luma.enrollments;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every query is bound to an academy; there is deliberately no lookup by id alone. */
interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {
    Optional<Enrollment> findByIdAndAcademyId(UUID id, UUID academyId);

    List<Enrollment> findByAcademyIdAndBatchId(UUID academyId, UUID batchId);

    List<Enrollment> findByAcademyIdAndStudentId(UUID academyId, UUID studentId);

    boolean existsByBatchIdAndStudentId(UUID batchId, UUID studentId);

    /** Enrollments in force on the day: started on or before it and not withdrawn by it. */
    @Query("""
            select e from Enrollment e
            where e.academyId = :academyId and e.batchId in :batchIds
              and e.enrolledOn <= :day and (e.withdrawnOn is null or e.withdrawnOn > :day)
            """)
    List<Enrollment> inForceOn(@Param("academyId") UUID academyId, @Param("batchIds") Collection<UUID> batchIds,
                               @Param("day") LocalDate day);
}
